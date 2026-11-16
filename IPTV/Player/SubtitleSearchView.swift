import SwiftUI

/// Untertitel online suchen (OpenSubtitles), laden und zeitlich verschieben.
struct SubtitleSearchView: View {
    @Environment(\.dismiss) private var dismiss
    let channel: Channel
    let engine: any PlaybackEngine

    @State private var results: [SubtitleResult] = []
    @State private var isLoading = true
    @State private var loadingID: Int?
    @State private var errorMessage: String?
    @State private var delay = 0.0

    private let service = SubtitleService.shared

    var body: some View {
        List {
            Section {
                Stepper(value: $delay, in: -30...30, step: 0.5) {
                    LabeledContent("Verschiebung") {
                        Text(delay == 0 ? "keine" : "\(delay > 0 ? "+" : "")\(delay.formatted(.number.precision(.fractionLength(1)))) s")
                            .monospacedDigit()
                    }
                }
                .onChange(of: delay) { _, value in engine.setSubtitleDelay(value) }

                if service.savedSubtitle(for: channel) != nil {
                    Button("Gewählten Untertitel entfernen", systemImage: "captions.bubble", role: .destructive) {
                        service.forgetSubtitle(for: channel)
                        engine.selectSubtitle(nil)
                        dismiss()
                    }
                }
            } footer: {
                Text("Erscheinen die Untertitel zu früh, erhöhe den Wert; kommen sie zu spät, verringere ihn.")
            }

            if let errorMessage {
                Section {
                    Label(errorMessage, systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.red)
                }
            }

            if isLoading {
                Section {
                    HStack {
                        Spacer()
                        ProgressView("Untertitel werden gesucht …")
                        Spacer()
                    }
                }
            } else if results.isEmpty, errorMessage == nil {
                Section {
                    ContentUnavailableView(
                        "Keine Untertitel gefunden",
                        systemImage: "captions.bubble",
                        description: Text("Für diesen Titel gibt es in deinen Sprachen keine Treffer. Die Sprachen änderst du unter Einstellungen → Untertitel.")
                    )
                }
            }

            let grouped = Dictionary(grouping: results, by: \.language)
            let order = results.map(\.language).reduce(into: [String]()) { if !$0.contains($1) { $0.append($1) } }
            ForEach(order, id: \.self) { language in
                Section(grouped[language]?.first?.languageName ?? language) {
                    ForEach(grouped[language] ?? []) { result in
                        Button {
                            Task { await choose(result) }
                        } label: {
                            HStack(spacing: 12) {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(result.release)
                                        .font(.subheadline)
                                        .lineLimit(2)
                                    HStack(spacing: 8) {
                                        Label(result.downloadCount.formatted(), systemImage: "arrow.down.circle")
                                        if result.isTrusted { Label("geprüft", systemImage: "checkmark.seal") }
                                        if result.isHearingImpaired { Label("für Hörgeschädigte", systemImage: "ear") }
                                    }
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                                }
                                Spacer(minLength: 8)
                                if loadingID == result.fileID {
                                    ProgressView()
                                } else {
                                    Image(systemName: "plus.circle.fill")
                                        .foregroundStyle(Theme.orange)
                                }
                            }
                            .contentShape(.rect)
                        }
                        .buttonStyle(.plain)
                        .disabled(loadingID != nil)
                    }
                }
            }

            if let remaining = service.remainingDownloads {
                Section {
                    Text("Heute noch \(remaining) Untertitel-Downloads verfügbar.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle("Untertitel")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Schließen", systemImage: "xmark") { dismiss() }
            }
        }
        .task {
            delay = engine.subtitleDelay
            do {
                results = try await service.search(for: channel)
            } catch {
                errorMessage = error.localizedDescription
            }
            isLoading = false
        }
    }

    private func choose(_ result: SubtitleResult) async {
        loadingID = result.fileID
        errorMessage = nil
        defer { loadingID = nil }
        do {
            let file = try await service.download(result, for: channel)
            engine.addSubtitle(file: file)
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
