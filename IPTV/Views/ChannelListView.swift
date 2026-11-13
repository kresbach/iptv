import SwiftUI

/// Senderliste mit Filter, aktueller Sendung (EPG) und Favoriten.
struct ChannelListView: View {
    @Environment(PlaylistStore.self) private var store
    @Environment(UserDataStore.self) private var userData
    @Environment(\.playChannel) private var playChannel

    let title: String
    let channels: [Channel]
    var showsCategory = true

    @State private var query = ""
    @State private var scheduleChannel: Channel?

    var body: some View {
        let filtered = store.search(query, in: channels)

        List(filtered) { channel in
            Button {
                playChannel(channel)
            } label: {
                ChannelRow(channel: channel, showsCategory: showsCategory)
            }
            .buttonStyle(.plain)
            .swipeActions(edge: .trailing) {
                FavoriteButton(isFavorite: userData.isFavorite(channel)) {
                    userData.toggleFavorite(channel)
                }
                .tint(Theme.orange)
            }
            .contextMenu {
                FavoriteButton(isFavorite: userData.isFavorite(channel)) {
                    userData.toggleFavorite(channel)
                }
                if channel.kind == .live {
                    Button("TV-Programm", systemImage: "calendar") { scheduleChannel = channel }
                }
            }
        }
        .listStyle(.plain)
        .navigationTitle(title)
        .navigationSubtitle("\(channels.count.formatted()) Einträge")
        .searchable(text: $query, prompt: "In \(title) suchen")
        .refreshable { await store.refresh() }
        .overlay {
            if filtered.isEmpty {
                if query.isEmpty {
                    ContentUnavailableView("Keine Einträge", systemImage: "tv.slash")
                } else {
                    ContentUnavailableView.search(text: query)
                }
            }
        }
        .sheet(item: $scheduleChannel) { channel in
            NavigationStack { EPGScheduleView(channel: channel) }
        }
    }
}

struct FavoriteButton: View {
    let isFavorite: Bool
    let action: () -> Void

    var body: some View {
        Button(
            isFavorite ? "Aus Favoriten entfernen" : "Zu Favoriten",
            systemImage: isFavorite ? "heart.slash.fill" : "heart.fill",
            action: action
        )
    }
}

struct ChannelRow: View {
    @Environment(PlaylistStore.self) private var store
    @Environment(UserDataStore.self) private var userData

    let channel: Channel
    var showsCategory = true

    var body: some View {
        HStack(spacing: 14) {
            ChannelBadge(channel: channel, size: 48)

            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(channel.name)
                        .font(.body.weight(.medium))
                        .lineLimit(1)
                    if userData.isFavorite(channel) {
                        Image(systemName: "heart.fill")
                            .font(.caption2)
                            .foregroundStyle(Theme.orange)
                            .accessibilityLabel("Favorit")
                    }
                }

                if channel.kind == .live, let guide = store.epg, !guide.schedule(for: channel).isEmpty {
                    NowPlayingLine(channel: channel, guide: guide)
                } else if let progress = userData.playbackProgress(for: channel), !progress.isFinished {
                    ProgressView(value: progress.fraction)
                        .tint(Theme.orange)
                        .frame(maxWidth: 160)
                } else if showsCategory, let group = channel.group {
                    Text(group)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }

            Spacer(minLength: 8)

            Image(systemName: "play.circle.fill")
                .font(.title2)
                .symbolRenderingMode(.hierarchical)
                .foregroundStyle(Theme.orange)
        }
        .padding(.vertical, 4)
        .contentShape(.rect)
        .accessibilityElement(children: .combine)
        .accessibilityHint("Abspielen")
    }
}

/// „Jetzt: Tagesschau“ mit Fortschrittsbalken, aktualisiert sich minütlich.
struct NowPlayingLine: View {
    let channel: Channel
    let guide: EPGGuide

    var body: some View {
        TimelineView(.everyMinute) { context in
            if let now = guide.current(for: channel, at: context.date) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(now.title)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    ProgressView(value: now.progress(at: context.date))
                        .tint(Theme.orange)
                        .frame(maxWidth: 160)
                }
            } else {
                Text("Kein Programm verfügbar")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            }
        }
    }
}

/// Senderlogo aus `tvg-logo`, mit orangefarbenem Kürzel als Platzhalter.
struct ChannelBadge: View {
    let channel: Channel
    var size: CGFloat = 48

    var body: some View {
        RoundedRectangle(cornerRadius: size * 0.26)
            .fill(Theme.gradient)
            .overlay {
                Text(channel.initials)
                    .font(.system(size: size * 0.32, weight: .bold, design: .rounded))
                    .foregroundStyle(.white)
                    .minimumScaleFactor(0.5)
                    .padding(4)
            }
            .overlay {
                if channel.logoURL != nil {
                    CachedImage(
                        url: channel.logoURL,
                        maxPixelSize: size * 3,
                        contentMode: .fit,
                        loadedBackground: Color(.secondarySystemBackground),
                        inset: size * 0.1
                    )
                }
            }
            .clipShape(.rect(cornerRadius: size * 0.26))
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

/// TV-Programm eines Senders für heute und morgen.
struct EPGScheduleView: View {
    @Environment(PlaylistStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @Environment(\.playChannel) private var playChannel
    let channel: Channel

    var body: some View {
        let schedule = (store.epg?.schedule(for: channel) ?? []).filter { $0.end > .now }
        let days = Dictionary(grouping: schedule) { Calendar.current.startOfDay(for: $0.start) }
            .sorted { $0.key < $1.key }

        List {
            ForEach(days, id: \.key) { day in
                Section(day.key.formatted(.dateTime.weekday(.wide).day().month(.wide))) {
                    ForEach(day.value) { programme in
                        ProgrammeRow(programme: programme)
                    }
                }
            }
        }
        .overlay {
            if schedule.isEmpty {
                ContentUnavailableView(
                    "Kein TV-Programm",
                    systemImage: "calendar.badge.exclamationmark",
                    description: Text("Für diesen Sender liegen keine Programmdaten vor.")
                )
            }
        }
        .navigationTitle(channel.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Schließen", systemImage: "xmark") { dismiss() }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("Ansehen", systemImage: "play.fill") {
                    dismiss()
                    // Erst das Sheet schließen lassen, dann den Player öffnen.
                    Task {
                        try? await Task.sleep(for: .milliseconds(450))
                        playChannel(channel)
                    }
                }
            }
        }
    }
}

struct ProgrammeRow: View {
    let programme: Programme

    var body: some View {
        TimelineView(.everyMinute) { context in
            let isLive = programme.isLive(at: context.date)
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(programme.timeRange)
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(isLive ? Theme.orange : .secondary)
                    if isLive {
                        Text("JETZT")
                            .font(.caption2.bold())
                            .foregroundStyle(.white)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Theme.gradient, in: .capsule)
                    }
                }
                Text(programme.title)
                    .font(.body.weight(isLive ? .semibold : .regular))
                if let description = programme.description {
                    Text(description)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(3)
                }
                if isLive {
                    ProgressView(value: programme.progress(at: context.date))
                        .tint(Theme.orange)
                }
            }
            .padding(.vertical, 2)
        }
    }
}
