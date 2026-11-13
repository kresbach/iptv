import SwiftUI

/// Startbildschirm mit großen Kacheln für Live TV, Filme und Serien.
struct HomeView: View {
    @Environment(PlaylistStore.self) private var store
    @Binding var selection: AppTab

    @State private var showsSettings = false

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                tile(for: .live, isLarge: true)

                HStack(spacing: 16) {
                    tile(for: .movie)
                    tile(for: .series)
                }

                PlaylistStatusCard()
            }
            .padding()
            .frame(maxWidth: 820)
            .frame(maxWidth: .infinity)
        }
        .background(Theme.backgroundGlow.ignoresSafeArea())
        .navigationTitle("IPTV")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Einstellungen", systemImage: "gearshape.fill") { showsSettings = true }
            }
        }
        .sheet(isPresented: $showsSettings) {
            NavigationStack { SettingsView() }
        }
        .refreshable { await store.refresh() }
    }

    private func tile(for kind: ContentKind, isLarge: Bool = false) -> some View {
        let count = store.section(kind).items.count
        return Button {
            selection = .section(kind)
        } label: {
            HomeTile(kind: kind, count: count, isLarge: isLarge)
        }
        .buttonStyle(.plain)
        .disabled(count == 0)
    }
}

private struct HomeTile: View {
    let kind: ContentKind
    let count: Int
    let isLarge: Bool

    var body: some View {
        VStack(alignment: .leading) {
            Image(systemName: kind.symbol)
                .font(.system(size: isLarge ? 44 : 34, weight: .semibold))
                .foregroundStyle(.white)
                .shadow(color: .black.opacity(0.15), radius: 6, y: 3)

            Spacer(minLength: 16)

            Text(kind.title)
                .font(isLarge ? .largeTitle.bold() : .title2.bold())
                .foregroundStyle(.white)
            Text(count > 0 ? "\(count.formatted()) \(kind.unit)" : "Keine Inhalte")
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.white.opacity(0.85))
        }
        .frame(maxWidth: .infinity, minHeight: isLarge ? 190 : 150, alignment: .leading)
        .padding(20)
        .background(alignment: .bottomTrailing) {
            // Großes, angeschnittenes Symbol als dezente Hintergrundgrafik.
            Image(systemName: kind.symbol)
                .font(.system(size: isLarge ? 170 : 120))
                .foregroundStyle(.white.opacity(0.12))
                .offset(x: 30, y: 30)
        }
        .background(Theme.gradient)
        .clipShape(.rect(cornerRadius: 28))
        .shadow(color: Theme.orange.opacity(0.35), radius: 16, y: 8)
        .opacity(count > 0 ? 1 : 0.5)
        .accessibilityElement(children: .combine)
    }
}

private struct PlaylistStatusCard: View {
    @Environment(PlaylistStore.self) private var store

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: "list.bullet.rectangle.fill")
                .font(.title2)
                .foregroundStyle(Theme.orange)

            VStack(alignment: .leading, spacing: 2) {
                Text("Playlist")
                    .font(.headline)
                if let lastUpdated = store.lastUpdated {
                    Text("Aktualisiert \(lastUpdated, format: .relative(presentation: .named))")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            Spacer()

            Button {
                Task { await store.refresh() }
            } label: {
                if store.isLoading {
                    ProgressView()
                } else {
                    Label("Aktualisieren", systemImage: "arrow.clockwise")
                }
            }
            .buttonStyle(.glass)
            .disabled(store.isLoading)
        }
        .padding(16)
        .glassEffect(in: .rect(cornerRadius: 24))
    }
}
