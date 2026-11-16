@preconcurrency import AVFoundation
import CoreMedia
import Foundation

/// Packt eine MKV-Datei ohne Qualitätsverlust in eine MP4-Datei um (Video und eine Tonspur
/// werden unverändert kopiert). Danach kann iOS die Datei wie jede MP4 verkleinern.
///
/// Unterstützt: Video in H.264 oder HEVC, Ton in AAC, AC-3 oder E-AC-3.
/// Nicht unterstützt: DTS/TrueHD-Ton, andere Videoformate, komprimierte Spuren.
/// In der Datei eingebettete Untertitel gehen verloren.
enum MKVRemuxer {

    enum RemuxError: LocalizedError {
        case invalidFile
        case unsupported(String)
        case writer(String)

        var errorDescription: String? {
            switch self {
            case .invalidFile: "Die MKV-Datei konnte nicht gelesen werden."
            case .unsupported(let what): "Diese MKV-Datei kann nicht umgewandelt werden: \(what)."
            case .writer(let detail): "Umwandeln fehlgeschlagen: \(detail)"
            }
        }
    }

    /// Annahme für die Bildumsortierung (B-Frames): höchstens so viele Bilder Versatz.
    private static let reorderDepth = 8
    private static let nanoseconds: CMTimeScale = 1_000_000_000

    static func remux(from source: URL, to destination: URL, progress: @escaping @Sendable (Double) -> Void) async throws {
        let demuxer = try MKVDemuxer(url: source)
        let tracks = try demuxer.readTracks()

        // Videospur wählen.
        guard let video = tracks.first(where: { $0.type == 1 }) else {
            throw RemuxError.unsupported("keine Videospur gefunden")
        }
        guard !video.hasContentEncoding else { throw RemuxError.unsupported("komprimierte Videospur") }
        let videoDescription = try makeVideoDescription(for: video)

        // Tonspur wählen: bevorzugt Deutsch, sonst die erste unterstützte.
        let audioTracks = tracks.filter { $0.type == 2 }
        let supported = audioTracks.filter { audioFormat(for: $0.codecID) != nil && !$0.hasContentEncoding }
        let audio = supported.first { ["ger", "deu", "de"].contains($0.language) } ?? supported.first
        if audio == nil, let first = audioTracks.first {
            throw RemuxError.unsupported("Tonformat \(first.codecID.replacingOccurrences(of: "A_", with: "")) wird nicht unterstützt")
        }
        let audioDescription = try audio.map { try makeAudioDescription(for: $0) }

        // Writer vorbereiten (Durchreichen ohne Neukodierung).
        try? FileManager.default.removeItem(at: destination)
        let writer = try AVAssetWriter(outputURL: destination, fileType: .mp4)
        let videoInput = AVAssetWriterInput(mediaType: .video, outputSettings: nil, sourceFormatHint: videoDescription)
        videoInput.expectsMediaDataInRealTime = false
        guard writer.canAdd(videoInput) else { throw RemuxError.unsupported("Videoformat lässt sich nicht in MP4 schreiben") }
        writer.add(videoInput)

        var audioInput: AVAssetWriterInput?
        if let audioDescription {
            let input = AVAssetWriterInput(mediaType: .audio, outputSettings: nil, sourceFormatHint: audioDescription.description)
            input.expectsMediaDataInRealTime = false
            guard writer.canAdd(input) else { throw RemuxError.unsupported("Tonformat lässt sich nicht in MP4 schreiben") }
            writer.add(input)
            audioInput = input
        }

        // MKV kennt nur Anzeigezeiten. Für MP4 werden Dekodierzeiten gebraucht; alles wird
        // um einige Bilder nach hinten geschoben, damit sie nie negativ werden.
        let frameDuration = video.defaultDurationNs > 0 ? video.defaultDurationNs : 41_708_333
        let shift = Int64(reorderDepth) * frameDuration

        guard writer.startWriting() else { throw RemuxError.writer(writer.error?.localizedDescription ?? "Start nicht möglich") }
        writer.startSession(atSourceTime: CMTime(value: shift, timescale: nanoseconds))

        var pendingTimes: [Int64] = []   // sortierte Anzeigezeiten für die Berechnung der Dekodierzeiten
        var lastDecodeTime = Int64.min
        var packetCount = 0
        var lastReported = -1

        do {
            while let packet = try demuxer.nextPacket() {
                packetCount += 1
                if packetCount % 64 == 0 {
                    try Task.checkCancellation()
                    let percent = Int(demuxer.progress * 100)
                    if percent != lastReported {
                        lastReported = percent
                        progress(demuxer.progress)
                    }
                }

                if packet.track == video.number {
                    let pts = packet.timeNs + shift
                    if pendingTimes.isEmpty && lastDecodeTime == Int64.min {
                        // Vorbelegen, damit die Dekodierzeit der Anzeigezeit um `reorderDepth` Bilder hinterherläuft.
                        pendingTimes = (0..<reorderDepth).map { pts - Int64(reorderDepth - $0) * frameDuration }
                    }
                    let index = pendingTimes.firstIndex { $0 > pts } ?? pendingTimes.count
                    pendingTimes.insert(pts, at: index)
                    var dts = pendingTimes.removeFirst()
                    if dts <= lastDecodeTime { dts = lastDecodeTime + 1 }
                    lastDecodeTime = dts

                    let sample = try makeSample(
                        data: packet.data,
                        description: videoDescription,
                        pts: CMTime(value: pts, timescale: nanoseconds),
                        dts: CMTime(value: dts, timescale: nanoseconds),
                        duration: .invalid,
                        isKeyframe: packet.isKeyframe
                    )
                    try await append(sample, to: videoInput, writer: writer)
                } else if let audio, let audioInput, let audioDescription, packet.track == audio.number {
                    let sample = try makeSample(
                        data: packet.data,
                        description: audioDescription.description,
                        pts: CMTime(value: packet.timeNs + shift, timescale: nanoseconds),
                        dts: .invalid,
                        duration: audioDescription.packetDuration,
                        isKeyframe: true
                    )
                    try await append(sample, to: audioInput, writer: writer)
                }
            }
        } catch {
            writer.cancelWriting()
            try? FileManager.default.removeItem(at: destination)
            throw error
        }

        guard lastDecodeTime != Int64.min else {
            writer.cancelWriting()
            try? FileManager.default.removeItem(at: destination)
            throw RemuxError.unsupported("keine Videodaten gefunden")
        }

        videoInput.markAsFinished()
        audioInput?.markAsFinished()
        await writer.finishWriting()
        guard writer.status == .completed else {
            try? FileManager.default.removeItem(at: destination)
            throw RemuxError.writer(writer.error?.localizedDescription ?? "unbekannter Fehler")
        }
        progress(1)
    }

    // MARK: - Schreiben

    private static func append(_ sample: CMSampleBuffer, to input: AVAssetWriterInput, writer: AVAssetWriter) async throws {
        var waited = 0
        while !input.isReadyForMoreMediaData {
            guard writer.status == .writing else {
                throw RemuxError.writer(writer.error?.localizedDescription ?? "Schreiben abgebrochen")
            }
            try await Task.sleep(for: .milliseconds(5))
            waited += 1
            if waited > 12_000 { throw RemuxError.writer("Zeitüberschreitung beim Schreiben") }  // 60 s
        }
        guard input.append(sample) else {
            throw RemuxError.writer(writer.error?.localizedDescription ?? "Bild konnte nicht geschrieben werden")
        }
    }

    private static func makeSample(
        data: Data,
        description: CMFormatDescription,
        pts: CMTime,
        dts: CMTime,
        duration: CMTime,
        isKeyframe: Bool
    ) throws -> CMSampleBuffer {
        var blockBuffer: CMBlockBuffer?
        var status = CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault,
            memoryBlock: nil,
            blockLength: data.count,
            blockAllocator: kCFAllocatorDefault,
            customBlockSource: nil,
            offsetToData: 0,
            dataLength: data.count,
            flags: kCMBlockBufferAssureMemoryNowFlag,
            blockBufferOut: &blockBuffer
        )
        guard status == noErr, let blockBuffer else { throw RemuxError.writer("Speicherfehler (\(status))") }

        status = data.withUnsafeBytes { raw in
            CMBlockBufferReplaceDataBytes(
                with: raw.baseAddress!,
                blockBuffer: blockBuffer,
                offsetIntoDestination: 0,
                dataLength: data.count
            )
        }
        guard status == noErr else { throw RemuxError.writer("Speicherfehler (\(status))") }

        var timing = CMSampleTimingInfo(duration: duration, presentationTimeStamp: pts, decodeTimeStamp: dts)
        var size = data.count
        var sample: CMSampleBuffer?
        status = CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault,
            dataBuffer: blockBuffer,
            formatDescription: description,
            sampleCount: 1,
            sampleTimingEntryCount: 1,
            sampleTimingArray: &timing,
            sampleSizeEntryCount: 1,
            sampleSizeArray: &size,
            sampleBufferOut: &sample
        )
        guard status == noErr, let sample else { throw RemuxError.writer("Sample-Fehler (\(status))") }

        if !isKeyframe,
           let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: true),
           CFArrayGetCount(attachments) > 0 {
            let dictionary = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(
                dictionary,
                Unmanaged.passUnretained(kCMSampleAttachmentKey_NotSync).toOpaque(),
                Unmanaged.passUnretained(kCFBooleanTrue).toOpaque()
            )
        }
        return sample
    }

    // MARK: - Formatbeschreibungen

    private static func makeVideoDescription(for track: MKVDemuxer.Track) throws -> CMFormatDescription {
        let codec: CMVideoCodecType
        let atomName: String
        switch track.codecID {
        case "V_MPEG4/ISO/AVC":
            codec = kCMVideoCodecType_H264
            atomName = "avcC"
        case "V_MPEGH/ISO/HEVC":
            codec = kCMVideoCodecType_HEVC
            atomName = "hvcC"
        default:
            throw RemuxError.unsupported("Videoformat \(track.codecID.replacingOccurrences(of: "V_", with: "")) wird nicht unterstützt")
        }
        guard let codecPrivate = track.codecPrivate, !codecPrivate.isEmpty, track.width > 0, track.height > 0 else {
            throw RemuxError.unsupported("Videospur ohne Formatangaben")
        }

        let extensions: [CFString: Any] = [
            kCMFormatDescriptionExtension_SampleDescriptionExtensionAtoms: [atomName: codecPrivate],
        ]
        var description: CMFormatDescription?
        let status = CMVideoFormatDescriptionCreate(
            allocator: kCFAllocatorDefault,
            codecType: codec,
            width: Int32(track.width),
            height: Int32(track.height),
            extensions: extensions as CFDictionary,
            formatDescriptionOut: &description
        )
        guard status == noErr, let description else { throw RemuxError.unsupported("Videoformat nicht lesbar (\(status))") }
        return description
    }

    private struct AudioDescription {
        let description: CMFormatDescription
        let packetDuration: CMTime
    }

    private static func audioFormat(for codecID: String) -> AudioFormatID? {
        if codecID.hasPrefix("A_AAC") { return kAudioFormatMPEG4AAC }
        if codecID == "A_AC3" { return kAudioFormatAC3 }
        if codecID == "A_EAC3" { return kAudioFormatEnhancedAC3 }
        return nil
    }

    private static let aacSampleRates: [Double] = [
        96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350,
    ]

    private static func makeAudioDescription(for track: MKVDemuxer.Track) throws -> AudioDescription {
        guard let format = audioFormat(for: track.codecID) else {
            throw RemuxError.unsupported("Tonformat wird nicht unterstützt")
        }
        var sampleRate = track.sampleRate > 0 ? track.sampleRate : 48000
        var channels = UInt32(max(track.channels, 1))
        var framesPerPacket: UInt32 = 1536
        var formatFlags: UInt32 = 0
        var cookie = Data()

        if format == kAudioFormatMPEG4AAC {
            framesPerPacket = 1024
            // AudioSpecificConfig: 5 Bit Objekttyp, 4 Bit Abtastraten-Index, 4 Bit Kanäle.
            if let config = track.codecPrivate, config.count >= 2 {
                cookie = config
                let first = config[config.startIndex]
                let second = config[config.startIndex + 1]
                let objectType = UInt32(first >> 3)
                let rateIndex = Int((first & 0x07) << 1 | second >> 7)
                let channelConfig = UInt32((second >> 3) & 0x0F)
                formatFlags = objectType
                if aacSampleRates.indices.contains(rateIndex) { sampleRate = aacSampleRates[rateIndex] }
                if channelConfig > 0 { channels = channelConfig == 7 ? 8 : channelConfig }
            }
        }

        var asbd = AudioStreamBasicDescription(
            mSampleRate: sampleRate,
            mFormatID: format,
            mFormatFlags: formatFlags,
            mBytesPerPacket: 0,
            mFramesPerPacket: framesPerPacket,
            mBytesPerFrame: 0,
            mChannelsPerFrame: channels,
            mBitsPerChannel: 0,
            mReserved: 0
        )

        var description: CMAudioFormatDescription?
        let status: OSStatus
        if cookie.isEmpty {
            status = CMAudioFormatDescriptionCreate(
                allocator: kCFAllocatorDefault,
                asbd: &asbd,
                layoutSize: 0,
                layout: nil,
                magicCookieSize: 0,
                magicCookie: nil,
                extensions: nil,
                formatDescriptionOut: &description
            )
        } else {
            status = cookie.withUnsafeBytes { raw in
                CMAudioFormatDescriptionCreate(
                    allocator: kCFAllocatorDefault,
                    asbd: &asbd,
                    layoutSize: 0,
                    layout: nil,
                    magicCookieSize: cookie.count,
                    magicCookie: raw.baseAddress,
                    extensions: nil,
                    formatDescriptionOut: &description
                )
            }
        }
        guard status == noErr, let description else { throw RemuxError.unsupported("Tonformat nicht lesbar (\(status))") }
        return AudioDescription(
            description: description,
            packetDuration: CMTime(value: CMTimeValue(framesPerPacket), timescale: CMTimeScale(sampleRate))
        )
    }
}

// MARK: - MKV lesen

/// Liest Spuren und Datenpakete aus einer Matroska-Datei, ohne sie ganz in den Speicher zu laden.
final class MKVDemuxer {
    struct Track {
        var number = 0
        var type = 0            // 1 = Video, 2 = Ton
        var codecID = ""
        var codecPrivate: Data?
        var defaultDurationNs: Int64 = 0
        var language = "eng"
        var width = 0
        var height = 0
        var sampleRate: Double = 0
        var channels = 0
        var hasContentEncoding = false
    }

    struct Packet {
        let track: Int
        let timeNs: Int64
        let isKeyframe: Bool
        let data: Data
    }

    private enum ID {
        static let segment: UInt32 = 0x1853_8067
        static let info: UInt32 = 0x1549_A966
        static let timecodeScale: UInt32 = 0x2AD7B1
        static let tracks: UInt32 = 0x1654_AE6B
        static let trackEntry: UInt32 = 0xAE
        static let trackNumber: UInt32 = 0xD7
        static let trackType: UInt32 = 0x83
        static let codecID: UInt32 = 0x86
        static let codecPrivate: UInt32 = 0x63A2
        static let defaultDuration: UInt32 = 0x23E383
        static let language: UInt32 = 0x22B59C
        static let contentEncodings: UInt32 = 0x6D80
        static let video: UInt32 = 0xE0
        static let pixelWidth: UInt32 = 0xB0
        static let pixelHeight: UInt32 = 0xBA
        static let audio: UInt32 = 0xE1
        static let samplingFrequency: UInt32 = 0xB5
        static let channels: UInt32 = 0x9F
        static let cluster: UInt32 = 0x1F43_B675
        static let clusterTimecode: UInt32 = 0xE7
        static let simpleBlock: UInt32 = 0xA3
        static let blockGroup: UInt32 = 0xA0
        static let block: UInt32 = 0xA1
        static let referenceBlock: UInt32 = 0xFB
    }

    private let handle: FileHandle
    private let fileSize: UInt64
    private var buffer = Data()
    private var cursor = 0
    private var bufferStart: UInt64 = 0

    private var timecodeScale: Int64 = 1_000_000
    private var clusterTimecode: Int64 = 0
    private var tracks: [Track] = []
    private var reachedFirstCluster = false

    init(url: URL) throws {
        handle = try FileHandle(forReadingFrom: url)
        fileSize = UInt64(DownloadManager.fileSize(url))
    }

    deinit { try? handle.close() }

    /// Anteil der bereits gelesenen Datei (0 … 1).
    var progress: Double {
        fileSize > 0 ? min(Double(bufferStart + UInt64(cursor)) / Double(fileSize), 1) : 0
    }

    /// Liest den Dateikopf bis zum ersten Datenblock und liefert die Spuren.
    func readTracks() throws -> [Track] {
        while !reachedFirstCluster {
            guard let header = try readHeader() else { break }
            try handleTopLevel(header)
        }
        guard !tracks.isEmpty else { throw MKVRemuxer.RemuxError.invalidFile }
        return tracks
    }

    /// Nächstes Datenpaket in Dateireihenfolge, `nil` am Ende.
    func nextPacket() throws -> Packet? {
        while let header = try readHeader() {
            switch header.id {
            case ID.simpleBlock:
                guard let size = header.size else { throw MKVRemuxer.RemuxError.invalidFile }
                let data = try read(Int(size))
                if let packet = try parseBlock(data, isSimple: true, hasReference: false) { return packet }
            case ID.blockGroup:
                guard let size = header.size else { throw MKVRemuxer.RemuxError.invalidFile }
                let children = try Self.children(of: try read(Int(size)))
                let hasReference = children.contains { $0.id == ID.referenceBlock }
                if let block = children.first(where: { $0.id == ID.block }),
                   let packet = try parseBlock(block.data, isSimple: false, hasReference: hasReference) {
                    return packet
                }
            default:
                try handleTopLevel(header)
            }
        }
        return nil
    }

    // MARK: Elemente

    private func handleTopLevel(_ header: (id: UInt32, size: UInt64?)) throws {
        switch header.id {
        case ID.segment:
            break  // hineingehen
        case ID.cluster:
            reachedFirstCluster = true
            clusterTimecode = 0
        case ID.clusterTimecode:
            guard let size = header.size, size <= 8 else { throw MKVRemuxer.RemuxError.invalidFile }
            clusterTimecode = Int64(Self.unsigned(try read(Int(size))))
        case ID.info:
            guard let size = header.size else { throw MKVRemuxer.RemuxError.invalidFile }
            for child in try Self.children(of: try read(Int(size))) where child.id == ID.timecodeScale {
                timecodeScale = max(Int64(Self.unsigned(child.data)), 1)
            }
        case ID.tracks:
            guard let size = header.size else { throw MKVRemuxer.RemuxError.invalidFile }
            for child in try Self.children(of: try read(Int(size))) where child.id == ID.trackEntry {
                tracks.append(try Self.parseTrack(child.data))
            }
        default:
            guard let size = header.size else { throw MKVRemuxer.RemuxError.invalidFile }
            try skip(size)
        }
    }

    private static func parseTrack(_ data: Data) throws -> Track {
        var track = Track()
        for child in try children(of: data) {
            switch child.id {
            case ID.trackNumber: track.number = Int(unsigned(child.data))
            case ID.trackType: track.type = Int(unsigned(child.data))
            case ID.codecID: track.codecID = string(child.data)
            case ID.codecPrivate: track.codecPrivate = child.data
            case ID.defaultDuration: track.defaultDurationNs = Int64(unsigned(child.data))
            case ID.language: track.language = string(child.data).lowercased()
            case ID.contentEncodings: track.hasContentEncoding = true
            case ID.video:
                for item in try children(of: child.data) {
                    if item.id == ID.pixelWidth { track.width = Int(unsigned(item.data)) }
                    if item.id == ID.pixelHeight { track.height = Int(unsigned(item.data)) }
                }
            case ID.audio:
                for item in try children(of: child.data) {
                    if item.id == ID.samplingFrequency { track.sampleRate = float(item.data) }
                    if item.id == ID.channels { track.channels = Int(unsigned(item.data)) }
                }
            default: break
            }
        }
        return track
    }

    /// Block: Spurnummer (variabel), relative Zeit (16 Bit), Flags, Daten.
    private func parseBlock(_ data: Data, isSimple: Bool, hasReference: Bool) throws -> Packet? {
        let bytes = data.startIndex
        guard data.count >= 4 else { return nil }
        let first = data[bytes]
        let numberLength = first.leadingZeroBitCount + 1
        guard numberLength <= 4, data.count >= numberLength + 3 else { return nil }
        var track = Int(first) & (0xFF >> numberLength)
        for offset in 1..<numberLength { track = track << 8 | Int(data[bytes + offset]) }

        let high = data[bytes + numberLength]
        let low = data[bytes + numberLength + 1]
        let relative = Int64(Int16(bitPattern: UInt16(high) << 8 | UInt16(low)))
        let flags = data[bytes + numberLength + 2]
        guard flags & 0x06 == 0 else {
            throw MKVRemuxer.RemuxError.unsupported("gebündelte Datenblöcke (Lacing)")
        }

        let payload = data.subdata(in: (bytes + numberLength + 3)..<data.endIndex)
        guard !payload.isEmpty else { return nil }
        return Packet(
            track: track,
            timeNs: (clusterTimecode + relative) * timecodeScale,
            isKeyframe: isSimple ? flags & 0x80 != 0 : !hasReference,
            data: payload
        )
    }

    // MARK: EBML-Grundlagen

    /// Liest Element-ID und Größe; `size == nil` bedeutet „unbekannte Größe“.
    private func readHeader() throws -> (id: UInt32, size: UInt64?)? {
        guard let first = try readByte() else { return nil }
        let idLength = first.leadingZeroBitCount + 1
        guard idLength <= 4 else { throw MKVRemuxer.RemuxError.invalidFile }
        var id = UInt32(first)
        for _ in 1..<idLength {
            guard let byte = try readByte() else { return nil }
            id = id << 8 | UInt32(byte)
        }

        guard let sizeFirst = try readByte() else { return nil }
        let sizeLength = sizeFirst.leadingZeroBitCount + 1
        guard sizeLength <= 8 else { throw MKVRemuxer.RemuxError.invalidFile }
        let mask: UInt8 = sizeLength == 8 ? 0 : 0xFF >> sizeLength
        var size = UInt64(sizeFirst & mask)
        var allOnes = sizeFirst & mask == mask
        for _ in 1..<sizeLength {
            guard let byte = try readByte() else { return nil }
            size = size << 8 | UInt64(byte)
            if byte != 0xFF { allOnes = false }
        }
        return (id, allOnes ? nil : size)
    }

    /// Zerlegt ein im Speicher liegendes Element in seine Kindelemente.
    private static func children(of data: Data) throws -> [(id: UInt32, data: Data)] {
        var result: [(id: UInt32, data: Data)] = []
        var index = data.startIndex
        let end = data.endIndex

        while index < end {
            let first = data[index]
            let idLength = first.leadingZeroBitCount + 1
            guard idLength <= 4, index + idLength < end else { break }
            var id = UInt32(first)
            for offset in 1..<idLength { id = id << 8 | UInt32(data[index + offset]) }
            index += idLength

            let sizeFirst = data[index]
            let sizeLength = sizeFirst.leadingZeroBitCount + 1
            guard sizeLength <= 8, index + sizeLength <= end else { break }
            let mask: UInt8 = sizeLength == 8 ? 0 : 0xFF >> sizeLength
            var size = UInt64(sizeFirst & mask)
            for offset in 1..<sizeLength { size = size << 8 | UInt64(data[index + offset]) }
            index += sizeLength

            guard size <= UInt64(end - index) else { break }
            let next = index + Int(size)
            result.append((id, data.subdata(in: index..<next)))
            index = next
        }
        return result
    }

    private static func unsigned(_ data: Data) -> UInt64 {
        data.prefix(8).reduce(0) { $0 << 8 | UInt64($1) }
    }

    private static func float(_ data: Data) -> Double {
        switch data.count {
        case 4: Double(Float(bitPattern: UInt32(truncatingIfNeeded: unsigned(data))))
        case 8: Double(bitPattern: unsigned(data))
        default: 0
        }
    }

    private static func string(_ data: Data) -> String {
        String(decoding: data.prefix { $0 != 0 }, as: UTF8.self)
    }

    // MARK: Gepuffertes Lesen

    private static let chunkSize = 1 << 20

    /// Stellt sicher, dass mindestens `count` Bytes im Puffer liegen. `false` am Dateiende.
    private func fill(_ count: Int) throws -> Bool {
        if buffer.count - cursor >= count { return true }
        var rest = cursor < buffer.count ? Data(buffer[cursor...]) : Data()
        bufferStart += UInt64(cursor)
        cursor = 0
        while rest.count < count {
            guard let chunk = try handle.read(upToCount: max(Self.chunkSize, count - rest.count)), !chunk.isEmpty else {
                buffer = rest
                return false
            }
            rest.append(chunk)
        }
        buffer = rest
        return true
    }

    private func readByte() throws -> UInt8? {
        guard try fill(1) else { return nil }
        defer { cursor += 1 }
        return buffer[cursor]
    }

    private func read(_ count: Int) throws -> Data {
        guard count >= 0, count <= 256 * 1024 * 1024, try fill(count) else {
            throw MKVRemuxer.RemuxError.invalidFile
        }
        defer { cursor += count }
        return buffer.subdata(in: cursor..<(cursor + count))
    }

    private func skip(_ count: UInt64) throws {
        if count <= UInt64(buffer.count - cursor) {
            cursor += Int(count)
            return
        }
        let target = bufferStart + UInt64(cursor) + count
        try handle.seek(toOffset: min(target, fileSize))
        buffer = Data()
        cursor = 0
        bufferStart = min(target, fileSize)
    }
}
