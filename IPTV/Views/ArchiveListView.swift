import SwiftUI

/// Vergangene Sendungen eines Senders aus dem Archiv, nach Tagen gruppiert (neueste zuerst).
struct ArchiveListView: View {
    private var store: PlaylistStore { .shared }
    @Environment(\.dismiss) private var dismiss
    let channel: Channel
    /// Wird mit dem abspielbaren Archiv-Eintrag aufgerufen.
    let onSelect: (Channel) -> Void

    var body: some View {
        let past = Catchup.pastProgrammes(for: channel, guide: store.epg)
        let days = Dictionary(grouping: past) { Calendar.current.startOfDay(for: $0.start) }
            .sorted { $0.key > $1.key }

        List {
            ForEach(days, id: \.key) { day in
                Section(day.key.formatted(.dateTime.weekday(.wide).day().month(.wide))) {
                    ForEach(day.value) { programme in
                        Button {
                            if let archived = store.archiveChannel(for: channel, programme: programme) {
                                onSelect(archived)
                            }
                        } label: {
                            HStack(spacing: 12) {
                                ProgrammeRow(programme: programme)
                                Spacer(minLength: 8)
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
        .overlay {
            if past.isEmpty {
                ContentUnavailableView(
                    "Keine verpassten Sendungen",
                    systemImage: "clock.arrow.circlepath",
                    description: Text("Für diesen Sender liegen keine vergangenen Sendungen im TV-Guide vor. Aktualisiere den TV-Guide in den Einstellungen, damit ältere Sendungen geladen werden.")
                )
            }
        }
        .navigationTitle("Verpasste Sendungen")
        .navigationSubtitle(channel.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Schließen", systemImage: "xmark") { dismiss() }
            }
        }
    }
}
