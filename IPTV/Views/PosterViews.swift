import SwiftUI

/// Filme als Poster-Raster (Cover aus `tvg-logo` bzw. Xtream).
struct MovieGridView: View {
    private var store: PlaylistStore { .shared }
    private var userData: UserDataStore { .shared }
    private var downloads: DownloadManager { .shared }
    @Environment(\.playChannel) private var playChannel

    let title: String
    let movies: [Channel]

    @State private var query = ""
    @AppStorage(MediaSort.movieStorageKey) private var sortOrder: MediaSort = .standard
    @AppStorage(MediaSort.topRatedStorageKey) private var onlyTopRated = false
    @State private var sizeProgress: (done: Int, total: Int)?
    @State private var sortCache = SortCache<Channel>()

    /// Höchstens so viele Größen werden pro Durchgang beim Server abgefragt.
    private static let sizeFetchLimit = 300

    private func sorted(_ list: [Channel]) -> [Channel] {
        var list = list
        if onlyTopRated {
            list = list.filter { ($0.rating ?? 0) >= MediaSort.topRatedThreshold }
        }
        guard sortOrder.needsSizes else {
            return sortOrder.apply(to: list, title: \.name, rating: \.rating, added: \.added, year: \.year)
        }
        // Unbekannte Größen immer ans Ende.
        let known = list.compactMap { movie in downloads.knownSize(for: movie).map { (movie, $0) } }
        let knownIDs = Set(known.map { $0.0.id })
        let ordered = known.sorted { sortOrder == .smallest ? $0.1 < $1.1 : $0.1 > $1.1 }.map { $0.0 }
        return ordered + list.filter { !knownIDs.contains($0.id) }
    }

    var body: some View {
        // Größen kommen während des Ladens nach, daher dort nicht zwischenspeichern.
        let filtered = sortOrder.needsSizes
            ? sorted(store.search(query, in: movies))
            : sortCache.value(for: SortCacheKey(
                sort: sortOrder, onlyTopRated: onlyTopRated, query: query, count: movies.count,
                firstID: movies.first.map { AnyHashable($0.id) }, lastID: movies.last.map { AnyHashable($0.id) }
            )) { sorted(store.search(query, in: movies)) }

        ScrollView {
            LazyVGrid(columns: PosterCard.columns, spacing: 18) {
                ForEach(filtered) { movie in
                    NavigationLink(value: movie) {
                        PosterCard(
                            title: movie.name,
                            subtitle: downloads.knownSize(for: movie).map { DownloadManager.format($0) },
                            imageURL: movie.logoURL,
                            symbol: "film.fill",
                            progress: userData.playbackProgress(for: movie).flatMap { $0.isFinished ? nil : $0.fraction },
                            isFavorite: userData.isFavorite(movie),
                            rating: movie.rating
                        )
                    }
                    .buttonStyle(.plain)
                    .contextMenu {
                        Button("Direkt abspielen", systemImage: "play.fill") { playChannel(movie) }
                        OpenInWindowButton(channel: movie)
                        if downloads.item(for: movie) == nil {
                            Button("Herunterladen", systemImage: "arrow.down.circle") {
                                downloads.download(movie, title: MediaInfo.cleanTitle(movie.name))
                            }
                        }
                        Divider()
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
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                MediaSortMenu(
                    sort: $sortOrder,
                    onlyTopRated: $onlyTopRated,
                    options: MediaSort.movieOptions,
                    hasRatings: movies.contains { $0.rating != nil }
                )
            }
        }
        .safeAreaInset(edge: .bottom) {
            if let sizeProgress, sizeProgress.done < sizeProgress.total {
                HStack(spacing: 10) {
                    ProgressView(value: Double(sizeProgress.done), total: Double(max(sizeProgress.total, 1)))
                        .tint(Theme.orange)
                    Text("Größen: \(sizeProgress.done) von \(sizeProgress.total)")
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                .padding(.horizontal)
                .padding(.vertical, 8)
                .background(.bar)
            }
        }
        // Beim Sortieren nach Größe die fehlenden Größen nachladen; endet beim Verlassen der Liste.
        .task(id: sortOrder) {
            guard sortOrder.needsSizes else {
                sizeProgress = nil
                return
            }
            await downloads.fetchSizes(for: movies, limit: Self.sizeFetchLimit) { done, total in
                sizeProgress = (done, total)
            }
            sizeProgress = nil
        }
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
    private var store: PlaylistStore { .shared }
    private var userData: UserDataStore { .shared }

    let title: String
    let shows: [SeriesShow]

    @State private var query = ""
    @AppStorage(MediaSort.seriesStorageKey) private var sortOrder: MediaSort = .standard
    @AppStorage(MediaSort.topRatedStorageKey) private var onlyTopRated = false
    @State private var sortCache = SortCache<SeriesShow>()

    private func sorted(_ list: [SeriesShow]) -> [SeriesShow] {
        var list = list
        if onlyTopRated {
            list = list.filter { ($0.rating ?? 0) >= MediaSort.topRatedThreshold }
        }
        return sortOrder.apply(to: list, title: \.title, rating: \.rating, added: \.added, year: \.year)
    }

    var body: some View {
        let filtered = sortCache.value(for: SortCacheKey(
            sort: sortOrder, onlyTopRated: onlyTopRated, query: query, count: shows.count,
            firstID: shows.first.map { AnyHashable($0.id) }, lastID: shows.last.map { AnyHashable($0.id) }
        )) { sorted(store.searchShows(query, in: shows)) }

        ScrollView {
            LazyVGrid(columns: PosterCard.columns, spacing: 18) {
                ForEach(filtered) { show in
                    NavigationLink(value: show) {
                        PosterCard(
                            title: show.title,
                            subtitle: show.episodes.isEmpty ? nil : "\(show.episodes.count) Episoden",
                            imageURL: show.posterURL,
                            symbol: "rectangle.stack.fill",
                            isFavorite: userData.isFavorite(show),
                            rating: show.rating
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
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                MediaSortMenu(
                    sort: $sortOrder,
                    onlyTopRated: $onlyTopRated,
                    options: MediaSort.seriesOptions,
                    hasRatings: shows.contains { $0.rating != nil }
                )
            }
        }
        .overlay {
            if filtered.isEmpty {
                if onlyTopRated && query.isEmpty && !shows.isEmpty {
                    ContentUnavailableView(
                        "Keine Serien mit ★ 7+",
                        systemImage: "star.slash",
                        description: Text("Schalte „Nur ★ 7+“ im Sortieren-Menü aus, um alle Serien zu sehen.")
                    )
                } else if query.isEmpty {
                    ContentUnavailableView("Keine Serien", systemImage: "rectangle.stack")
                } else {
                    ContentUnavailableView.search(text: query)
                }
            }
        }
    }
}

/// Serie mit Infos (Handlung, Besetzung, Trailer) sowie Staffeln und Episoden.
/// Bei Xtream werden Infos und Episoden beim Öffnen geladen.
struct SeriesDetailView: View {
    private var store: PlaylistStore { .shared }
    private var userData: UserDataStore { .shared }
    private var downloads: DownloadManager { .shared }
    let show: SeriesShow

    @State private var info: MediaInfo?
    @State private var episodes: [SeriesShow.Episode] = []
    @State private var isLoading = false
    @State private var errorMessage: String?
    @State private var selectedSeason: Int?

    private var seasons: [(season: Int, episodes: [SeriesShow.Episode])] {
        SeriesShow.seasons(of: episodes)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                DetailHeader(
                    title: show.title,
                    backdropURL: info?.backdropURL ?? info?.posterURL ?? show.posterURL,
                    posterURL: info?.posterURL ?? show.posterURL,
                    symbol: ContentKind.series.symbol
                )

                VStack(alignment: .leading, spacing: 18) {
                    MetaRow(info: info, category: show.category)

                    if !episodes.isEmpty {
                        Text("\(seasons.count) \(seasons.count == 1 ? "Staffel" : "Staffeln") · \(episodes.count) Episoden")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(Theme.orange)
                    }

                    HStack(spacing: 12) {
                        TrailerButton(title: show.title, info: info)
                        FavoriteToggle(isFavorite: userData.isFavorite(show)) {
                            userData.toggleFavorite(show)
                        }
                    }

                    InfoDetails(info: info ?? MediaInfo(plot: show.plot))

                    episodeSection
                }
                .padding(.horizontal)
            }
            .padding(.bottom, 32)
        }
        .navigationTitle(show.title)
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    @ViewBuilder
    private var episodeSection: some View {
        if isLoading {
            ProgressView("Episoden werden geladen …")
                .frame(maxWidth: .infinity)
                .padding(.vertical)
        } else if let errorMessage {
            VStack(alignment: .leading, spacing: 8) {
                Label(errorMessage, systemImage: "exclamationmark.triangle.fill")
                    .foregroundStyle(.red)
                Button("Erneut versuchen", systemImage: "arrow.clockwise") {
                    Task { await load() }
                }
            }
        } else if !seasons.isEmpty {
            let current = selectedSeason ?? seasons.first?.season ?? 0

            VStack(alignment: .leading, spacing: 12) {
                if seasons.count > 1 {
                    Picker("Staffel", selection: Binding(get: { current }, set: { selectedSeason = $0 })) {
                        ForEach(seasons, id: \.season) { season in
                            Text(seasonTitle(season.season)).tag(season.season)
                        }
                    }
                    .pickerStyle(.menu)
                    .tint(Theme.orange)
                } else {
                    Text(seasonTitle(current)).font(.headline)
                }

                let seasonEpisodes = seasons.first { $0.season == current }?.episodes ?? []
                Button {
                    for episode in seasonEpisodes {
                        downloads.download(
                            episode.channel,
                            title: episode.displayTitle,
                            subtitle: Self.episodeSubtitle(episode, show: show),
                            posterURL: show.posterURL,
                            showTitle: MediaInfo.cleanTitle(show.title)
                        )
                    }
                } label: {
                    Label("\(seasonTitle(current)) herunterladen", systemImage: "arrow.down.circle")
                        .font(.subheadline.weight(.semibold))
                }
                .disabled(seasonEpisodes.allSatisfy { downloads.item(for: $0.channel) != nil })

                VStack(spacing: 0) {
                    ForEach(seasons.first { $0.season == current }?.episodes ?? []) { episode in
                        EpisodeRow(episode: episode, show: show, queue: episodes.map(\.channel))
                            .padding(.vertical, 10)
                        Divider()
                    }
                }
            }
        }
    }

    private func seasonTitle(_ season: Int) -> String {
        season > 0 ? "Staffel \(season)" : "Episoden"
    }

    /// z. B. „Staffel 1, Episode 3“ für die Downloads-Übersicht.
    static func episodeSubtitle(_ episode: SeriesShow.Episode, show: SeriesShow) -> String {
        switch (episode.season, episode.number) {
        case let (season?, number?): "Staffel \(season), Episode \(number)"
        case let (nil, number?): "Episode \(number)"
        default: MediaInfo.cleanTitle(show.title)
        }
    }

    private func load() async {
        guard episodes.isEmpty else { return }
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            let details = try await store.seriesDetails(for: show)
            info = details.info
            episodes = details.episodes
        } catch {
            errorMessage = HTTPClient.message(for: error)
        }
    }
}

private struct EpisodeRow: View {
    private var userData: UserDataStore { .shared }
    private var downloads: DownloadManager { .shared }
    @Environment(\.playChannel) private var playChannel
    let episode: SeriesShow.Episode
    let show: SeriesShow
    /// Alle Episoden der Serie in Reihenfolge, für „Nächste Episode“.
    let queue: [Channel]

    var body: some View {
        HStack(spacing: 12) {
            Button {
                playChannel(episode.channel, queue: queue)
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

            DownloadButton(
                channel: episode.channel,
                title: episode.displayTitle,
                subtitle: SeriesDetailView.episodeSubtitle(episode, show: show),
                posterURL: show.posterURL,
                showTitle: MediaInfo.cleanTitle(show.title),
                style: .compact
            )
        }
        .contextMenu {
            Button("Abspielen", systemImage: "play.fill") { playChannel(episode.channel, queue: queue) }
            OpenInWindowButton(channel: episode.channel, queue: queue)
            Divider()
            if let item = downloads.item(for: episode.channel) {
                Button("Download löschen", systemImage: "trash", role: .destructive) { downloads.remove(item.id) }
            } else {
                Button("Herunterladen", systemImage: "arrow.down.circle") {
                    downloads.download(
                        episode.channel,
                        title: episode.displayTitle,
                        subtitle: SeriesDetailView.episodeSubtitle(episode, show: show),
                        posterURL: show.posterURL,
                        showTitle: MediaInfo.cleanTitle(show.title)
                    )
                }
            }
        }
    }
}

/// Poster im Hochformat mit orangefarbenem Platzhalter, solange (oder falls) kein Cover lädt.
struct PosterCard: View {
    /// Passt die Spaltenzahl der Fensterbreite an; auf dem Mac etwas größere Poster.
    static let columns = [GridItem(.adaptive(minimum: AppRouter.isMac ? 150 : 110), spacing: 14)]

    let title: String?
    let subtitle: String?
    let imageURL: URL?
    let symbol: String
    var progress: Double?
    var isFavorite = false
    var rating: Double?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Theme.gradient
                .overlay {
                    Image(systemName: symbol)
                        .font(.largeTitle)
                        .foregroundStyle(.white.opacity(0.8))
                }
                .overlay {
                    if imageURL != nil {
                        CachedImage(url: imageURL, maxPixelSize: 360)
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
                .overlay(alignment: .topLeading) {
                    if let rating {
                        RatingBadge(rating: rating)
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
