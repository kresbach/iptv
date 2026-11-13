import SwiftUI

struct SettingsView: View {
    @Environment(PlaylistStore.self) private var store
    @Environment(\.dismiss) private var dismiss

    @State private var showsSetup = false
    @State private var confirmsRemoval = false

    var body: some View {
        List {
            Section("Playlist") {
                if let url = store.sourceURL {
                    LabeledContent("Adresse") {
                        Text(url.absoluteString)
                            .lineLimit(2)
                            .truncationMode(.middle)
                            .textSelection(.enabled)
                    }
                }
                ForEach(ContentKind.allCases) { kind in
                    LabeledContent(kind.title, value: store.section(kind).items.count, format: .number)
                }
                if let lastUpdated = store.lastUpdated {
                    LabeledContent("Aktualisiert") {
                        Text(lastUpdated, format: .relative(presentation: .named))
                    }
                }
            }

            Section {
                Button {
                    Task { await store.refresh() }
                } label: {
                    HStack {
                        Label("Jetzt aktualisieren", systemImage: "arrow.clockwise")
                        Spacer()
                        if store.isLoading { ProgressView() }
                    }
                }
                .disabled(store.isLoading)

                Button("Andere Playlist laden", systemImage: "link.badge.plus") {
                    showsSetup = true
                }

                Button("Playlist entfernen", systemImage: "trash", role: .destructive) {
                    confirmsRemoval = true
                }
            } footer: {
                if let error = store.errorMessage, !showsSetup {
                    Text(error).foregroundStyle(.red)
                }
            }

            Section("Info") {
                LabeledContent("Version", value: Bundle.main.appVersion)
            }
        }
        .navigationTitle("Einstellungen")
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Fertig", systemImage: "checkmark") { dismiss() }
            }
        }
        .sheet(isPresented: $showsSetup) {
            NavigationStack {
                PlaylistSetupView(isSheet: true)
            }
        }
        .confirmationDialog(
            "Playlist wirklich entfernen?",
            isPresented: $confirmsRemoval,
            titleVisibility: .visible
        ) {
            Button("Entfernen", role: .destructive) {
                dismiss()
                store.removePlaylist()
            }
        } message: {
            Text("Die gespeicherte Senderliste wird von diesem Gerät gelöscht.")
        }
    }
}

private extension Bundle {
    var appVersion: String {
        let version = infoDictionary?["CFBundleShortVersionString"] as? String ?? "–"
        let build = infoDictionary?["CFBundleVersion"] as? String ?? "–"
        return "\(version) (\(build))"
    }
}
