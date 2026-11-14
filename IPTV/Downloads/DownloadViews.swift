import SwiftUI

/// Download-Knopf für Filme und Episoden. Zeigt je nach Zustand Laden, Fortschritt,
/// „Heruntergeladen“ oder einen Fehler mit erneutem Versuch.
struct DownloadButton: View {
    enum Style { case large, compact }

    @Environment(DownloadManager.self) private var downloads
    let channel: Channel
    let title: String
    var subtitle: String?
    var posterURL: URL?
    var showTitle: String?
    var style: Style = .large

    @State private var confirmsDelete = false

    var body: some View {
        let item = downloads.item(for: channel)

        Group {
            switch style {
            case .large: largeButton(item)
            case .compact: compactButton(item)
            }
        }
        .confirmationDialog("Download löschen?", isPresented: $confirmsDelete, titleVisibility: .visible) {
            Button("Löschen", role: .destructive) {
                if let item { downloads.remove(item.id) }
            }
        } message: {
            Text("Die Datei wird vom Gerät entfernt. Streamen ist weiterhin möglich.")
        }
    }

    private func start() {
        downloads.download(channel, title: title, subtitle: subtitle, posterURL: posterURL, showTitle: showTitle)
    }

    @ViewBuilder
    private func largeButton(_ item: DownloadItem?) -> some View {
        Button {
            guard let item else { return start() }
            switch item.state {
            case .completed, .queued, .downloading, .paused: confirmsDelete = true
            case .failed: downloads.retry(item.id)
            }
        } label: {
            HStack(spacing: 10) {
                DownloadStateIcon(item: item)
                Text(largeTitle(item))
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.glass)
        .controlSize(.large)
    }

    private func largeTitle(_ item: DownloadItem?) -> String {
        guard let item else { return "Herunterladen" }
        switch item.state {
        case .queued: return "In Warteschlange"
        case .paused: return "Pausiert"
        case .downloading:
            return item.totalBytes > 0 ? "Lädt … \(Int(item.fraction * 100)) %" : "Lädt …"
        case .completed:
            return "Heruntergeladen · \(DownloadManager.format(item.totalBytes))"
        case .failed: return "Fehler – erneut versuchen"
        }
    }

    private func compactButton(_ item: DownloadItem?) -> some View {
        Button {
            guard let item else { return start() }
            if case .failed = item.state { downloads.retry(item.id) } else { confirmsDelete = true }
        } label: {
            DownloadStateIcon(item: item)
                .font(.title3)
                .frame(width: 32, height: 32)
                .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(item == nil ? "Herunterladen" : "Download verwalten")
    }
}

/// Symbol bzw. Fortschrittsring für einen Download-Zustand.
struct DownloadStateIcon: View {
    let item: DownloadItem?

    var body: some View {
        switch item?.state {
        case nil:
            Image(systemName: "arrow.down.circle")
                .foregroundStyle(Theme.orange)
        case .queued?, .paused?:
            Image(systemName: "clock.arrow.circlepath")
                .foregroundStyle(.secondary)
        case .downloading?:
            ZStack {
                Circle().stroke(Theme.orange.opacity(0.25), lineWidth: 3)
                Circle()
                    .trim(from: 0, to: max(item?.fraction ?? 0, 0.03))
                    .stroke(Theme.orange, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                Image(systemName: "stop.fill")
                    .font(.system(size: 8))
                    .foregroundStyle(Theme.orange)
            }
            .frame(width: 20, height: 20)
        case .completed?:
            Image(systemName: "checkmark.circle.fill")
                .foregroundStyle(.green)
        case .failed?:
            Image(systemName: "exclamationmark.circle.fill")
                .foregroundStyle(.red)
        }
    }
}

/// Übersicht aller Downloads: laufende mit Fortschritt, fertige zum Abspielen.
struct DownloadsView: View {
    @Environment(DownloadManager.self) private var downloads
    @Environment(\.playChannel) private var playChannel

    var body: some View {
        List {
            if !downloads.activeItems.isEmpty || !downloads.failedItems.isEmpty {
                Section("Warteschlange") {
                    ForEach(downloads.activeItems + downloads.failedItems) { item in
                        DownloadRow(item: item)
                            .swipeActions {
                                Button("Abbrechen", systemImage: "xmark", role: .destructive) {
                                    downloads.remove(item.id)
                                }
                            }
                    }
                }
            }

            let completed = downloads.completedItems
            if !completed.isEmpty {
                let movies = completed.filter { $0.showTitle == nil }
                if !movies.isEmpty {
                    Section("Filme") {
                        ForEach(movies) { item in completedRow(item) }
                    }
                }
                let shows = Dictionary(grouping: completed.filter { $0.showTitle != nil }) { $0.showTitle ?? "" }
                ForEach(shows.keys.sorted(), id: \.self) { show in
                    Section(show) {
                        ForEach(shows[show] ?? []) { item in completedRow(item) }
                    }
                }
            }
        }
        .overlay {
            if downloads.items.isEmpty {
                ContentUnavailableView(
                    "Keine Downloads",
                    systemImage: "arrow.down.circle",
                    description: Text("Lade Filme oder Episoden über den Download-Knopf in der Detailansicht herunter, um sie offline anzuschauen.")
                )
            }
        }
        .safeAreaInset(edge: .bottom) {
            if !downloads.completedItems.isEmpty {
                Text("Belegt: \(DownloadManager.format(downloads.usedBytes)) · Frei: \(DownloadManager.format(DownloadManager.availableBytes))")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .padding(8)
                    .frame(maxWidth: .infinity)
                    .background(.bar)
            }
        }
        .navigationTitle("Downloads")
    }

    private func completedRow(_ item: DownloadItem) -> some View {
        Button {
            playChannel(item.channel)
        } label: {
            DownloadRow(item: item)
        }
        .buttonStyle(.plain)
        .swipeActions {
            Button("Löschen", systemImage: "trash", role: .destructive) {
                downloads.remove(item.id)
            }
        }
        .contextMenu {
            Button("Abspielen", systemImage: "play.fill") { playChannel(item.channel) }
            Button("Löschen", systemImage: "trash", role: .destructive) { downloads.remove(item.id) }
        }
    }
}

private struct DownloadRow: View {
    @Environment(DownloadManager.self) private var downloads
    @Environment(UserDataStore.self) private var userData
    let item: DownloadItem

    var body: some View {
        HStack(spacing: 14) {
            PosterCard(title: nil, subtitle: nil, imageURL: item.posterURL, symbol: item.channel.kind.symbol)
                .frame(width: 46)

            VStack(alignment: .leading, spacing: 4) {
                Text(item.title)
                    .font(.body.weight(.medium))
                    .lineLimit(2)
                if let subtitle = item.subtitle {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                status
            }

            Spacer(minLength: 8)

            if item.isCompleted {
                Image(systemName: "play.circle.fill")
                    .font(.title2)
                    .symbolRenderingMode(.hierarchical)
                    .foregroundStyle(Theme.orange)
            } else if case .failed = item.state {
                Button("Erneut", systemImage: "arrow.clockwise") { downloads.retry(item.id) }
                    .labelStyle(.iconOnly)
                    .buttonStyle(.borderless)
            }
        }
        .padding(.vertical, 2)
    }

    @ViewBuilder
    private var status: some View {
        switch item.state {
        case .queued:
            Text("Wartet …").font(.caption).foregroundStyle(.secondary)
        case .paused:
            Text("Pausiert während der Wiedergabe").font(.caption).foregroundStyle(.secondary)
        case .downloading:
            VStack(alignment: .leading, spacing: 2) {
                ProgressView(value: item.fraction).tint(Theme.orange)
                Text(item.totalBytes > 0
                     ? "\(DownloadManager.format(item.bytesWritten)) von \(DownloadManager.format(item.totalBytes))"
                     : DownloadManager.format(item.bytesWritten))
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
        case .completed:
            HStack(spacing: 6) {
                Text(DownloadManager.format(item.totalBytes))
                if let progress = userData.playbackProgress(for: item.channel), !progress.isFinished, progress.position > 30 {
                    Text("· angefangen")
                } else if userData.playbackProgress(for: item.channel)?.isFinished == true {
                    Text("· gesehen")
                }
            }
            .font(.caption)
            .foregroundStyle(.secondary)
        case .failed(let message):
            Text(message).font(.caption).foregroundStyle(.red).lineLimit(2)
        }
    }
}

/// Leiste „Downloads“ auf dem Startbildschirm (auch offline nutzbar).
struct DownloadsShelf: View {
    @Environment(DownloadManager.self) private var downloads
    @Environment(\.playChannel) private var playChannel

    var body: some View {
        let completed = Array(downloads.completedItems.sorted { $0.addedAt > $1.addedAt }.prefix(15))
        if !completed.isEmpty || !downloads.activeItems.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                NavigationLink {
                    DownloadsView()
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "arrow.down.circle.fill").foregroundStyle(Theme.orange)
                        Text("Downloads").font(.title3.bold())
                        if !downloads.activeItems.isEmpty {
                            Text("\(downloads.activeItems.count) laden")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.white)
                                .padding(.horizontal, 8)
                                .padding(.vertical, 3)
                                .background(Theme.gradient, in: .capsule)
                        }
                        Image(systemName: "chevron.right")
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(.secondary)
                    }
                }
                .buttonStyle(.plain)

                if !completed.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(alignment: .top, spacing: 14) {
                            ForEach(completed) { item in
                                Button {
                                    playChannel(item.channel)
                                } label: {
                                    PosterCard(
                                        title: item.title,
                                        subtitle: item.subtitle,
                                        imageURL: item.posterURL,
                                        symbol: item.channel.kind.symbol
                                    )
                                    .frame(width: 120)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                    .scrollClipDisabled()
                }
            }
        }
    }
}
