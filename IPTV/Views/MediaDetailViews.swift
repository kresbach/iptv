import SwiftUI

/// Detailansicht eines Films: Hintergrundbild, Cover, Eckdaten, Handlung,
/// Besetzung sowie Abspielen/Weiterschauen und Trailer.
struct MovieDetailView: View {
    private var store: PlaylistStore { .shared }
    private var userData: UserDataStore { .shared }
    private var downloads: DownloadManager { .shared }
    @Environment(\.playChannel) private var playChannel

    let movie: Channel

    @State private var info: MediaInfo?
    @State private var isLoading = true
    @State private var versions: [MovieVersion] = []
    @State private var ownSize: Int64?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                DetailHeader(
                    title: movie.name,
                    backdropURL: info?.backdropURL ?? info?.posterURL ?? movie.logoURL,
                    posterURL: info?.posterURL ?? movie.logoURL,
                    symbol: ContentKind.movie.symbol
                )

                VStack(alignment: .leading, spacing: 18) {
                    MetaRow(info: info, category: movie.group)

                    playButtons

                    DownloadButton(
                        channel: movie,
                        title: MediaInfo.cleanTitle(movie.name),
                        subtitle: [info?.year, info?.durationText].compactMap { $0 }.joined(separator: " · "),
                        posterURL: info?.posterURL ?? movie.logoURL
                    )

                    HStack(spacing: 12) {
                        TrailerButton(title: movie.name, info: info)
                        FavoriteToggle(isFavorite: userData.isFavorite(movie)) {
                            userData.toggleFavorite(movie)
                        }
                    }

                    if isLoading {
                        ProgressView("Infos werden geladen …")
                            .frame(maxWidth: .infinity)
                    }

                    InfoDetails(info: info)

                    if !versions.isEmpty {
                        MovieVersionsList(versions: versions, ownSize: ownSize)
                    }
                }
                .padding(.horizontal)
            }
            .padding(.bottom, 32)
        }
        .navigationTitle(movie.name)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            info = await store.movieInfo(for: movie)
            isLoading = false
            await loadVersions()
        }
    }

    /// Sucht andere Fassungen desselben Films und fragt nacheinander ihre Dateigrößen ab.
    private func loadVersions() async {
        let found = await store.alternativeVersions(of: movie)
        guard !found.isEmpty, !Task.isCancelled else { return }
        versions = found.map { MovieVersion(channel: $0, size: nil) }
        ownSize = await downloads.expectedSize(for: movie)

        for channel in found {
            guard !Task.isCancelled else { return }
            let size = await downloads.expectedSize(for: channel)
            if let index = versions.firstIndex(where: { $0.channel.id == channel.id }) {
                versions[index].size = size
            }
        }
        // Kleinste zuerst, unbekannte Größen ans Ende.
        versions.sort { ($0.size ?? .max) < ($1.size ?? .max) }
    }

    @ViewBuilder
    private var playButtons: some View {
        if let progress = userData.playbackProgress(for: movie), progress.position > 30, !progress.isFinished {
            VStack(alignment: .leading, spacing: 10) {
                Button {
                    playChannel(movie)
                } label: {
                    Label("Weiterschauen", systemImage: "play.fill")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 4)
                }
                .buttonStyle(.glassProminent)
                .controlSize(.large)

                HStack {
                    ProgressView(value: progress.fraction).tint(Theme.orange)
                    Text("noch \(Duration.seconds(progress.remaining).formatted(.units(allowed: [.hours, .minutes], width: .abbreviated)))")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Button("Von vorne abspielen", systemImage: "arrow.counterclockwise") {
                    userData.clearProgress(for: movie)
                    playChannel(movie)
                }
                .font(.subheadline)
            }
        } else {
            Button {
                playChannel(movie)
            } label: {
                Label("Abspielen", systemImage: "play.fill")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 4)
            }
            .buttonStyle(.glassProminent)
            .controlSize(.large)
        }
    }
}

/// Eine weitere Fassung desselben Films mit ihrer Dateigröße.
struct MovieVersion: Identifiable {
    let channel: Channel
    var size: Int64?
    var id: UUID { channel.id }
}

/// Liste „Weitere Fassungen“ – zeigt, ob es den Film auch kleiner gibt.
private struct MovieVersionsList: View {
    let versions: [MovieVersion]
    let ownSize: Int64?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Weitere Fassungen").font(.headline)
            Text("Derselbe Film in anderer Qualität. Kleinere Fassungen sparen Speicher beim Herunterladen.")
                .font(.caption)
                .foregroundStyle(.secondary)

            VStack(spacing: 0) {
                ForEach(versions) { version in
                    NavigationLink(value: version.channel) {
                        HStack(spacing: 12) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(version.channel.name)
                                    .font(.subheadline.weight(.medium))
                                    .lineLimit(2)
                                    .multilineTextAlignment(.leading)
                                Text(version.channel.category)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                            }
                            Spacer(minLength: 8)
                            VStack(alignment: .trailing, spacing: 2) {
                                if let size = version.size {
                                    Text(DownloadManager.format(size))
                                        .font(.subheadline.monospacedDigit())
                                    if let ownSize, ownSize > 0, size < ownSize {
                                        Text("\(Int((1 - Double(size) / Double(ownSize)) * 100)) % kleiner")
                                            .font(.caption2.weight(.semibold))
                                            .foregroundStyle(.green)
                                    }
                                } else {
                                    Text("Größe unbekannt")
                                        .font(.caption)
                                        .foregroundStyle(.tertiary)
                                }
                            }
                            Image(systemName: "chevron.right")
                                .font(.footnote.weight(.semibold))
                                .foregroundStyle(.tertiary)
                        }
                        .padding(.vertical, 10)
                        .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                    Divider()
                }
            }
        }
    }
}

// MARK: - Bausteine (auch für Serien)

/// Hintergrundbild mit Verlauf, darüber Cover und Titel.
struct DetailHeader: View {
    let title: String
    let backdropURL: URL?
    let posterURL: URL?
    let symbol: String

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            Theme.gradient
                .overlay {
                    if backdropURL != nil {
                        CachedImage(url: backdropURL, maxPixelSize: 900)
                    }
                }
                .frame(height: 230)
                .clipped()
                .overlay {
                    LinearGradient(
                        colors: [.clear, Color(.systemBackground).opacity(0.6), Color(.systemBackground)],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                }

            HStack(alignment: .bottom, spacing: 16) {
                PosterCard(title: nil, subtitle: nil, imageURL: posterURL, symbol: symbol)
                    .frame(width: 110)
                Text(MediaInfo.cleanTitle(title))
                    .font(.title2.bold())
                    .lineLimit(3)
                    .padding(.bottom, 8)
            }
            .padding(.horizontal)
            .offset(y: 40)
        }
        .padding(.bottom, 40)
    }
}

/// Jahr · Dauer · Genre · Bewertung.
struct MetaRow: View {
    let info: MediaInfo?
    let category: String?

    var body: some View {
        let parts = [info?.year, info?.durationText, info?.genre ?? category].compactMap { $0 }
        HStack(spacing: 10) {
            if let rating = info?.rating, rating > 0 {
                Label(rating.formatted(.number.precision(.fractionLength(1))), systemImage: "star.fill")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Theme.orange)
            }
            Text(parts.joined(separator: " · "))
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .lineLimit(2)
        }
    }
}

/// Öffnet den YouTube-Trailer (oder eine YouTube-Suche, falls keiner hinterlegt ist).
struct TrailerButton: View {
    @Environment(\.openURL) private var openURL
    let title: String
    let info: MediaInfo?

    var body: some View {
        let url = info?.trailerURL ?? MediaInfo.trailerSearchURL(for: title, year: info?.year)
        Button {
            if let url { openURL(url) }
        } label: {
            Label(info?.trailerURL != nil ? "Trailer" : "Trailer suchen", systemImage: "play.rectangle.fill")
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.glass)
        .controlSize(.large)
        .disabled(url == nil)
    }
}

struct FavoriteToggle: View {
    let isFavorite: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: isFavorite ? "heart.fill" : "heart")
                .foregroundStyle(Theme.orange)
                .frame(width: 24)
        }
        .buttonStyle(.glass)
        .controlSize(.large)
        .accessibilityLabel(isFavorite ? "Aus Favoriten entfernen" : "Zu Favoriten")
    }
}

/// Handlung, Regie und Besetzung.
struct InfoDetails: View {
    let info: MediaInfo?
    @State private var showsFullPlot = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if let plot = info?.plot {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Handlung").font(.headline)
                    Text(plot)
                        .font(.body)
                        .foregroundStyle(.secondary)
                        .lineLimit(showsFullPlot ? nil : 5)
                    if plot.count > 280 {
                        Button(showsFullPlot ? "Weniger" : "Mehr") { showsFullPlot.toggle() }
                            .font(.subheadline.weight(.semibold))
                    }
                }
            }
            if let director = info?.director {
                detail("Regie", director)
            }
            if let cast = info?.cast {
                detail("Besetzung", cast)
            }
        }
    }

    private func detail(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.headline)
            Text(value).font(.subheadline).foregroundStyle(.secondary)
        }
    }
}
