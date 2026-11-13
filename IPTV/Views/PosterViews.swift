import SwiftUI

/// Filme als Poster-Raster (Cover aus `tvg-logo`).
struct MovieGridView: View {
    @Environment(PlaylistStore.self) private var store
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
                        PosterCard(title: movie.name, subtitle: nil, imageURL: movie.logoURL, symbol: "film.fill")
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding()
        }
        .navigationTitle(title)
        .navigationSubtitle("\(movies.count.formatted()) Filme")
        .searchable(text: $query, prompt: "Film suchen")
        .overlay {
            if filtered.isEmpty, !query.isEmpty {
                ContentUnavailableView.search(text: query)
            }
        }
    }
}

/// Serien als Poster-Raster; Episoden werden zu Serien zusammengefasst.
struct SeriesGridView: View {
    let title: String
    let episodes: [Channel]

    @State private var query = ""
    @State private var shows: [SeriesShow] = []

    var body: some View {
        let filtered = query.isEmpty ? shows : shows.filter { $0.title.localizedStandardContains(query) }

        ScrollView {
            LazyVGrid(columns: PosterCard.columns, spacing: 18) {
                ForEach(filtered) { show in
                    NavigationLink(value: show) {
                        PosterCard(
                            title: show.title,
                            subtitle: "\(show.episodes.count) Episoden",
                            imageURL: show.posterURL,
                            symbol: "rectangle.stack.fill"
                        )
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding()
        }
        .navigationTitle(title)
        .navigationSubtitle("\(shows.count.formatted()) Serien")
        .searchable(text: $query, prompt: "Serie suchen")
        .overlay {
            if filtered.isEmpty, !query.isEmpty {
                ContentUnavailableView.search(text: query)
            }
        }
        .task(id: episodes.count) {
            shows = SeriesShow.shows(from: episodes)
        }
    }
}

/// Staffeln und Episoden einer Serie.
struct SeriesDetailView: View {
    @Environment(\.playChannel) private var playChannel
    let show: SeriesShow

    var body: some View {
        List {
            Section {
                HStack(alignment: .top, spacing: 16) {
                    PosterCard(title: nil, subtitle: nil, imageURL: show.posterURL, symbol: "rectangle.stack.fill")
                        .frame(width: 110)
                    VStack(alignment: .leading, spacing: 6) {
                        Text(show.title)
                            .font(.title2.bold())
                        Text(show.category)
                            .foregroundStyle(.secondary)
                        Text("\(show.seasons.count) Staffeln · \(show.episodes.count) Episoden")
                            .font(.subheadline)
                            .foregroundStyle(Theme.orange)
                    }
                }
                .listRowBackground(Color.clear)
            }

            ForEach(show.seasons, id: \.season) { season in
                Section(seasonTitle(season.season)) {
                    ForEach(season.episodes) { episode in
                        Button {
                            playChannel(episode.channel)
                        } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(episode.title)
                                        .font(.body.weight(.medium))
                                    if episode.number != nil {
                                        Text(episode.channel.name)
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                            .lineLimit(1)
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
            }
        }
        .navigationTitle(show.title)
        .navigationBarTitleDisplayMode(.inline)
    }

    private func seasonTitle(_ season: Int) -> String {
        season > 0 ? "Staffel \(season)" : "Episoden"
    }
}

/// Poster im Hochformat mit orangefarbenem Platzhalter, solange (oder falls) kein Cover lädt.
struct PosterCard: View {
    static let columns = [GridItem(.adaptive(minimum: 110), spacing: 14)]

    let title: String?
    let subtitle: String?
    let imageURL: URL?
    let symbol: String

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
