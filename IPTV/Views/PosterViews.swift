import SwiftUI

/// Filme als Poster-Raster (Cover aus `tvg-logo` bzw. Xtream).
struct MovieGridView: View {
    @Environment(PlaylistStore.self) private var store
    @Environment(UserDataStore.self) private var userData
    @Environment(\.playChannel) private var playChannel

    let title: String
    let movies: [Channel]

    @State private var query = ""

    var body: some View {
        let filtered = store.search(query, in: movies)

        ScrollView {
            LazyVGrid(columns: PosterCard.columns, spacing: 18) {
                ForEach(filtered) { movie in
                    Button {
                        playChannel(movie)
                    } label: {
                        PosterCard(
                            title: movie.name,
                            subtitle: nil,
                            imageURL: movie.logoURL,
                            symbol: "film.fill",
                            progress: userData.playbackProgress(for: movie).flatMap { $0.isFinished ? nil : $0.fraction },
                            isFavorite: userData.isFavorite(movie)
                        )
                    }
                    .buttonStyle(.plain)
                    .contextMenu {
                        FavoriteButton(isFavorite: userData.isFavorite(movie)) {
                            userData.toggleFavorite(movie)
                        }
                    }
                }
            }
            .padding()
        }
        .navigationTitle(title)
        .navigationSubtitle("\(movies.count.formatted()) Filme")
        .searchable(text: $query, prompt: "Film suchen")
        .overlay {
            if filtered.isEmpty {
                if query.isEmpty {
                    ContentUnavailableView("Keine Filme", systemImage: "film")
                } else {
                    ContentUnavailableView.search(text: query)
                }
            }
        }
    }
}

/// Serien als Poster-Raster.
struct SeriesGridView: View {
    @Environment(PlaylistStore.self) private var store
    @Environment(UserDataStore.self) private var userData

    let title: String
    let shows: [SeriesShow]

    @State private var query = ""

    var body: some View {
        let filtered = store.searchShows(query, in: shows)

        ScrollView {
            LazyVGrid(columns: PosterCard.columns, spacing: 18) {
                ForEach(filtered) { show in
                    NavigationLink(value: show) {
                        PosterCard(
                            title: show.title,
                            subtitle: show.episodes.isEmpty ? nil : "\(show.episodes.count) Episoden",
                            imageURL: show.posterURL,
                            symbol: "rectangle.stack.fill",
                            isFavorite: userData.isFavorite(show)
                        )
                    }
                    .buttonStyle(.plain)
                    .contextMenu {
                        FavoriteButton(isFavorite: userData.isFavorite(show)) {
                            userData.toggleFavorite(show)
                        }
                    }
                }
            }
            .padding()
        }
        .navigationTitle(title)
        .navigationSubtitle("\(shows.count.formatted()) Serien")
        .searchable(text: $query, prompt: "Serie suchen")
        .overlay {
            if filtered.isEmpty {
                if query.isEmpty {
                    ContentUnavailableView("Keine Serien", systemImage: "rectangle.stack")
                } else {
                    ContentUnavailableView.search(text: query)
                }
            }
        }
    }
}

/// Staffeln und Episoden einer Serie. Bei Xtream werden sie beim Öffnen geladen.
struct SeriesDetailView: View {
    @Environment(PlaylistStore.self) private var store
    @Environment(UserDataStore.self) private var userData
    @Environment(\.playChannel) private var playChannel
    let show: SeriesShow

    @State private var episodes: [SeriesShow.Episode] = []
    @State private var isLoading = false
    @State private var errorMessage: String?

    var body: some View {
        List {
            Section {
                header
                    .listRowBackground(Color.clear)
            }

            if isLoading {
                Section {
                    HStack {
                        Spacer()
                        ProgressView("Episoden werden geladen …")
                        Spacer()
                    }
                }
            } else if let errorMessage {
                Section {
                    Label(errorMessage, systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.red)
                    Button("Erneut versuchen", systemImage: "arrow.clockwise") {
                        Task { await loadEpisodes() }
                    }
                }
            }

            ForEach(SeriesShow.seasons(of: episodes), id: \.season) { season in
                Section(seasonTitle(season.season)) {
                    ForEach(season.episodes) { episode in
                        EpisodeRow(episode: episode)
                    }
                }
            }
        }
        .navigationTitle(show.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button(
                    userData.isFavorite(show) ? "Aus Favoriten entfernen" : "Zu Favoriten",
                    systemImage: userData.isFavorite(show) ? "heart.fill" : "heart"
                ) {
                    userData.toggleFavorite(show)
                }
            }
        }
        .task { await loadEpisodes() }
    }

    private var header: some View {
        HStack(alignment: .top, spacing: 16) {
            PosterCard(title: nil, subtitle: nil, imageURL: show.posterURL, symbol: "rectangle.stack.fill")
                .frame(width: 110)
            VStack(alignment: .leading, spacing: 6) {
                Text(show.title)
                    .font(.title2.bold())
                Text(show.category)
                    .foregroundStyle(.secondary)
                if !episodes.isEmpty {
                    let seasons = SeriesShow.seasons(of: episodes).count
                    Text("\(seasons) \(seasons == 1 ? "Staffel" : "Staffeln") · \(episodes.count) Episoden")
                        .font(.subheadline)
                        .foregroundStyle(Theme.orange)
                }
                if let plot = show.plot, !plot.isEmpty {
                    Text(plot)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .lineLimit(6)
                }
            }
        }
    }

    private func seasonTitle(_ season: Int) -> String {
        season > 0 ? "Staffel \(season)" : "Episoden"
    }

    private func loadEpisodes() async {
        guard episodes.isEmpty else { return }
        guard show.needsEpisodeLoading else {
            episodes = show.episodes
            return
        }
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            episodes = try await store.episodes(for: show)
        } catch {
            errorMessage = HTTPClient.message(for: error)
        }
    }
}

private struct EpisodeRow: View {
    @Environment(UserDataStore.self) private var userData
    @Environment(\.playChannel) private var playChannel
    let episode: SeriesShow.Episode

    var body: some View {
        Button {
            playChannel(episode.channel)
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text(episode.displayTitle)
                        .font(.body.weight(.medium))
                    if let progress = userData.playbackProgress(for: episode.channel) {
                        if progress.isFinished {
                            Label("Gesehen", systemImage: "checkmark.circle.fill")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        } else {
                            ProgressView(value: progress.fraction)
                                .tint(Theme.orange)
                                .frame(maxWidth: 160)
                        }
                    }
                }
                Spacer()
                Image(systemName: "play.circle.fill")
                    .font(.title2)
                    .symbolRenderingMode(.hierarchical)
                    .foregroundStyle(Theme.orange)
            }
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }
}

/// Poster im Hochformat mit orangefarbenem Platzhalter, solange (oder falls) kein Cover lädt.
struct PosterCard: View {
    static let columns = [GridItem(.adaptive(minimum: 110), spacing: 14)]

    let title: String?
    let subtitle: String?
    let imageURL: URL?
    let symbol: String
    var progress: Double?
    var isFavorite = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Theme.gradient
                .overlay {
                    Image(systemName: symbol)
                        .font(.largeTitle)
                        .foregroundStyle(.white.opacity(0.8))
                }
                .overlay {
                    if let imageURL {
                        AsyncImage(url: imageURL) { phase in
                            if let image = phase.image {
                                image.resizable().scaledToFill()
                            }
                        }
                    }
                }
                .overlay(alignment: .topTrailing) {
                    if isFavorite {
                        Image(systemName: "heart.fill")
                            .font(.caption)
                            .foregroundStyle(.white)
                            .padding(6)
                            .background(Theme.orange, in: .circle)
                            .padding(6)
                    }
                }
                .overlay(alignment: .bottom) {
                    if let progress {
                        ProgressView(value: progress)
                            .tint(Theme.orange)
                            .padding(.horizontal, 8)
                            .padding(.bottom, 8)
                    }
                }
                .aspectRatio(2 / 3, contentMode: .fit)
                .clipShape(.rect(cornerRadius: 14))
                .shadow(color: .black.opacity(0.15), radius: 6, y: 3)

            if let title {
                Text(title)
                    .font(.footnote.weight(.semibold))
                    .lineLimit(2, reservesSpace: true)
                    .multilineTextAlignment(.leading)
            }
            if let subtitle {
                Text(subtitle)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .contentShape(.rect)
        .accessibilityElement(children: .combine)
    }
}
