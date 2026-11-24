import SwiftUI

/// TV-Programm als Zeitraster: Sender untereinander, Sendungen entlang der Zeitachse.
/// Die Senderspalte bleibt beim seitlichen Scrollen stehen.
struct TVGuideView: View {
    private var store: PlaylistStore { .shared }
    private var userData: UserDataStore { .shared }
    private var prefs: CategoryPreferences { .shared }
    @Environment(\.playChannel) private var playChannel

    /// Gewählte Kategorie; `favoritesTag` steht für die Favoriten.
    @State private var category: String?
    @State private var selection: Selection?
    @State private var windowStart = TVGuideView.currentWindowStart()

    private struct Selection: Identifiable {
        let channel: Channel
        let programme: Programme
        var id: String { "\(channel.stableKey)|\(programme.start.timeIntervalSince1970)" }
    }

    private static let favoritesTag = "\u{1}favoriten"
    private static let rowHeight: CGFloat = 64
    private static let headerHeight: CGFloat = 28
    private static let channelWidth: CGFloat = 96
    private static let minuteWidth: CGFloat = 4
    private static let hours = 12
    /// So weit reicht das Raster zurück, wenn Sender ein Archiv haben.
    private static let archiveHours = 24
    /// So viele Sender zeigt das Raster höchstens (flüssiges Scrollen).
    private static let maxChannels = 150

    /// Beginn des Rasters: die letzte volle halbe Stunde.
    private static func currentWindowStart() -> Date {
        let interval: TimeInterval = 30 * 60
        return Date(timeIntervalSince1970: (Date.now.timeIntervalSince1970 / interval).rounded(.down) * interval)
    }

    /// Zeitbereich des Rasters; mit Archiv beginnt es früher.
    private struct Timeline {
        let start: Date
        let hours: Int

        var end: Date { start.addingTimeInterval(TimeInterval(hours) * 3600) }
        var totalWidth: CGFloat { CGFloat(hours * 60) * TVGuideView.minuteWidth }

        func offset(for date: Date) -> CGFloat {
            CGFloat(date.timeIntervalSince(start) / 60) * TVGuideView.minuteWidth
        }
    }

    private func timeline(for channels: [Channel]) -> Timeline {
        let past = channels.contains(where: Catchup.isSupported) ? Self.archiveHours : 0
        return Timeline(
            start: windowStart.addingTimeInterval(-TimeInterval(past) * 3600),
            hours: Self.hours + past
        )
    }

    private var favoriteChannels: [Channel] {
        userData.inFavoriteOrder(prefs.filter(store.content.live.items.filter { userData.isFavorite($0) }))
    }

    /// Sender der gewählten Kategorie, für die es Programmdaten gibt.
    private func channels(guide: EPGGuide) -> [Channel] {
        let source: [Channel]
        if category == Self.favoritesTag {
            source = favoriteChannels
        } else if let category, !prefs.isBlocked(.live, category) {
            source = store.content.live.items(in: category)
        } else {
            source = []
        }
        return Array(source.lazy.filter { !guide.schedule(for: $0).isEmpty }.prefix(Self.maxChannels))
    }

    var body: some View {
        Group {
            if let guide = store.epg {
                let channels = channels(guide: guide)
                if channels.isEmpty {
                    ContentUnavailableView(
                        "Kein Programm",
                        systemImage: "calendar.badge.exclamationmark",
                        description: Text("Für die Sender dieser Kategorie liegen keine Programmdaten vor. Wähle oben eine andere Kategorie.")
                    )
                } else {
                    grid(channels: channels, guide: guide)
                }
            } else {
                ContentUnavailableView(
                    "Kein TV-Guide",
                    systemImage: "calendar.badge.exclamationmark",
                    description: Text("Für diese Playlist ist kein TV-Guide geladen. Du kannst unter Einstellungen → Playlists verwalten eine EPG-Adresse eintragen.")
                )
            }
        }
        .navigationTitle("TV-Programm")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Picker("Kategorie", selection: $category) {
                        if !favoriteChannels.isEmpty {
                            Label("Favoriten", systemImage: "heart.fill").tag(Optional(Self.favoritesTag))
                        }
                        ForEach(prefs.arranged(store.content.live.categories, kind: .live)) { item in
                            if !prefs.isBlocked(.live, item.name) {
                                Text(item.name).tag(Optional(item.name))
                            }
                        }
                    }
                } label: {
                    Label(category == Self.favoritesTag ? "Favoriten" : (category ?? "Kategorie"), systemImage: "line.3.horizontal.decrease.circle")
                        .labelStyle(.titleAndIcon)
                        .lineLimit(1)
                }
            }
        }
        .onAppear {
            windowStart = Self.currentWindowStart()
            if category == nil { category = defaultCategory() }
        }
        .sheet(item: $selection) { item in
            NavigationStack {
                programmeDetail(item)
            }
            .appEnvironment()
            .presentationDetents([.medium])
        }
    }

    /// Favoriten, sonst die erste Kategorie mit Programmdaten.
    private func defaultCategory() -> String? {
        guard let guide = store.epg else { return nil }
        if favoriteChannels.contains(where: { !guide.schedule(for: $0).isEmpty }) { return Self.favoritesTag }
        let categories = prefs.arranged(store.content.live.categories, kind: .live)
            .filter { !prefs.isBlocked(.live, $0.name) }
        return categories.first { item in
            store.content.live.items(in: item.name).contains { !guide.schedule(for: $0).isEmpty }
        }?.name ?? categories.first?.name
    }

    // MARK: - Raster

    private func grid(channels: [Channel], guide: EPGGuide) -> some View {
        let timeline = timeline(for: channels)
        return ScrollView(.vertical) {
            HStack(alignment: .top, spacing: 0) {
                // Feste Senderspalte
                LazyVStack(spacing: 0) {
                    Color.clear.frame(height: Self.headerHeight)
                    ForEach(channels) { channel in
                        Button {
                            playChannel(channel, queue: channels)
                        } label: {
                            VStack(spacing: 3) {
                                ChannelBadge(channel: channel, size: 34)
                                Text(channel.name)
                                    .font(.caption2)
                                    .lineLimit(1)
                            }
                            .frame(width: Self.channelWidth - 8, height: Self.rowHeight)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .frame(width: Self.channelWidth)

                // Seitlich scrollbare Zeitachse
                ScrollViewReader { proxy in
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyVStack(alignment: .leading, spacing: 0) {
                            timeHeader(timeline)
                            ForEach(channels) { channel in
                                programmeRow(channel: channel, channels: channels, guide: guide, timeline: timeline)
                            }
                        }
                        .frame(width: timeline.totalWidth, alignment: .leading)
                        .overlay(alignment: .topLeading) {
                            TimelineView(.everyMinute) { context in
                                // Rote Linie: aktuelle Uhrzeit
                                Rectangle()
                                    .fill(.red)
                                    .frame(width: 2)
                                    .offset(x: max(timeline.offset(for: context.date), 0))
                            }
                            .allowsHitTesting(false)
                        }
                        .background(alignment: .topLeading) {
                            // Sprungmarke: Beim Öffnen steht „jetzt“ links (vergangene Sendungen links davon).
                            Color.clear
                                .frame(width: 1, height: 1)
                                .padding(.leading, max(timeline.offset(for: windowStart), 0))
                                .id(Self.nowAnchor)
                        }
                    }
                    .onAppear { proxy.scrollTo(Self.nowAnchor, anchor: .leading) }
                    .onChange(of: category) { proxy.scrollTo(Self.nowAnchor, anchor: .leading) }
                }
            }
        }
    }

    private static let nowAnchor = "jetzt"

    private func timeHeader(_ timeline: Timeline) -> some View {
        ZStack(alignment: .topLeading) {
            ForEach(0..<(timeline.hours * 2), id: \.self) { slot in
                let date = timeline.start.addingTimeInterval(TimeInterval(slot) * 1800)
                // Um Mitternacht den Wochentag dazuschreiben.
                let isMidnight = Calendar.current.component(.hour, from: date) == 0
                    && Calendar.current.component(.minute, from: date) == 0
                Text(isMidnight
                     ? date.formatted(.dateTime.weekday(.abbreviated).hour().minute())
                     : date.formatted(date: .omitted, time: .shortened))
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(.secondary)
                    .offset(x: timeline.offset(for: date) + 4)
            }
        }
        .frame(width: timeline.totalWidth, height: Self.headerHeight, alignment: .topLeading)
    }

    private func programmeRow(channel: Channel, channels: [Channel], guide: EPGGuide, timeline: Timeline) -> some View {
        let programmes = guide.schedule(for: channel).filter { $0.end > timeline.start && $0.start < timeline.end }
        let hasArchive = Catchup.isSupported(channel)
        let now = Date.now

        return ZStack(alignment: .topLeading) {
            ForEach(programmes) { programme in
                let start = max(timeline.offset(for: programme.start), 0)
                let end = min(timeline.offset(for: programme.end), timeline.totalWidth)
                let isLive = programme.isLive(at: now)
                let isPast = programme.end <= now
                let inArchive = hasArchive && isPast && Catchup.isAvailable(channel, programme, now: now)

                Button {
                    selection = Selection(channel: channel, programme: programme)
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 3) {
                            if inArchive {
                                Image(systemName: "clock.arrow.circlepath")
                                    .font(.caption2)
                                    .foregroundStyle(Theme.orange)
                                    .accessibilityLabel("Im Archiv")
                            }
                            Text(programme.title)
                                .font(.caption.weight(.semibold))
                                .lineLimit(2)
                        }
                        Text(programme.start.formatted(date: .omitted, time: .shortened))
                            .font(.caption2.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                    .padding(6)
                    .frame(width: max(end - start - 2, 1), height: Self.rowHeight - 4, alignment: .topLeading)
                    .background(
                        isLive ? Theme.orange.opacity(0.28) : Color(.secondarySystemBackground),
                        in: .rect(cornerRadius: 8)
                    )
                    .clipped()
                    .opacity(isPast && !inArchive ? 0.55 : 1)
                    .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .offset(x: start, y: 2)
            }
        }
        .frame(width: timeline.totalWidth, height: Self.rowHeight, alignment: .topLeading)
    }

    // MARK: - Sendungsdetails

    private func programmeDetail(_ item: Selection) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 12) {
                    ChannelBadge(channel: item.channel, size: 44)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.channel.name).font(.subheadline.weight(.semibold))
                        Text("\(item.programme.start.formatted(.dateTime.weekday(.wide))) · \(item.programme.timeRange)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Text(item.programme.title)
                    .font(.title3.bold())
                if let description = item.programme.description {
                    Text(description)
                        .foregroundStyle(.secondary)
                }
                if Catchup.isAvailable(item.channel, item.programme),
                   let archived = store.archiveChannel(for: item.channel, programme: item.programme) {
                    Button {
                        selection = nil
                        Task {
                            try? await Task.sleep(for: .milliseconds(450))
                            playChannel(archived)
                        }
                    } label: {
                        Label(item.programme.isLive(at: .now) ? "Von Anfang an ansehen" : "Aus dem Archiv ansehen",
                              systemImage: "clock.arrow.circlepath")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.glassProminent)
                    .controlSize(.large)
                }
                Button {
                    let channel = item.channel
                    let queue = store.epg.map { channels(guide: $0) } ?? []
                    selection = nil
                    // Erst das Sheet schließen lassen, dann den Player öffnen.
                    Task {
                        try? await Task.sleep(for: .milliseconds(450))
                        playChannel(channel, queue: queue)
                    }
                } label: {
                    Label(item.programme.isLive(at: .now) ? "Jetzt ansehen" : "Sender ansehen", systemImage: "play.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.glassProminent)
                .controlSize(.large)
            }
            .padding()
        }
        .navigationTitle("Sendung")
        .navigationBarTitleDisplayMode(.inline)
    }
}
