import SwiftUI

/// Wiederverwendbare Senderliste mit eigener Filtersuche.
struct ChannelListView: View {
    @Environment(PlaylistStore.self) private var store
    @Environment(\.playChannel) private var playChannel

    let title: String
    let channels: [Channel]
    var showsCategory = true

    @State private var query = ""

    private var filtered: [Channel] {
        store.search(query, in: channels)
    }

    var body: some View {
        List(filtered) { channel in
            Button {
                playChannel(channel)
            } label: {
                ChannelRow(channel: channel, showsCategory: showsCategory)
            }
            .buttonStyle(.plain)
        }
        .listStyle(.plain)
        .navigationTitle(title)
        .navigationSubtitle("\(channels.count) Sender")
        .searchable(text: $query, prompt: "In \(title) suchen")
        .refreshable { await store.refresh() }
        .overlay {
            if filtered.isEmpty, !query.isEmpty {
                ContentUnavailableView.search(text: query)
            }
        }
    }
}

struct AllChannelsView: View {
    @Environment(PlaylistStore.self) private var store

    var body: some View {
        ChannelListView(title: "Alle Sender", channels: store.channels)
    }
}

struct ChannelRow: View {
    let channel: Channel
    var showsCategory = true

    var body: some View {
        HStack(spacing: 14) {
            Text(channel.initials)
                .font(.system(.subheadline, design: .rounded, weight: .bold))
                .foregroundStyle(.white)
                .minimumScaleFactor(0.6)
                .frame(width: 44, height: 44)
                .background(Theme.gradient, in: .rect(cornerRadius: 12))

            VStack(alignment: .leading, spacing: 2) {
                Text(channel.name)
                    .font(.body.weight(.medium))
                    .lineLimit(1)
                if showsCategory, let group = channel.group {
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
        .accessibilityHint("Sender abspielen")
    }
}
