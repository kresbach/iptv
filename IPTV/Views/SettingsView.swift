import SwiftUI

struct SettingsView: View {
    @Environment(PlaylistStore.self) private var store
    @Environment(UserDataStore.self) private var userData
    @Environment(\.dismiss) private var dismiss

    @AppStorage(PlayerEngine.storageKey) private var playerEngine: PlayerEngine = .automatic
    @State private var confirmsClearHistory = false

    var body: some View {
        List {
            if let playlist = store.activePlaylist {
                Section("Aktive Playlist") {
                    LabeledContent("Name", value: playlist.name)
                    LabeledContent("Quelle") {
                        Label(playlist.source.typeTitle, systemImage: playlist.source.symbol)
                    }
                    ForEach(ContentKind.allCases) { kind in
                        LabeledContent(kind.title, value: store.content.count(of: kind), format: .number)
                    }
                    if let lastUpdated = playlist.lastUpdated {
                        LabeledContent("Aktualisiert") {
                            Text(lastUpdated, format: .relative(presentation: .named))
                        }
                    }
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
                }
            }

            Section {
                NavigationLink {
                    PlaylistsView()
                } label: {
                    LabeledContent("Playlists verwalten") {
                        Text("\(store.playlists.count)")
                    }
                }
            } footer: {
                if let error = store.errorMessage {
                    Text(error).foregroundStyle(.red)
                }
            }

            Section {
                if let url = store.activePlaylist?.epgURL {
                    LabeledContent("Quelle") {
                        Text(url.host() ?? url.absoluteString)
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                    if let epg = store.epg {
                        LabeledContent("Sender mit Programm", value: epg.programmes.count, format: .number)
                        LabeledContent("Geladen") {
                            Text(epg.fetchedAt, format: .relative(presentation: .named))
                        }
                    }
                    Button {
                        Task { await store.refreshEPG(force: true) }
                    } label: {
                        HStack {
                            Label("TV-Guide aktualisieren", systemImage: "calendar.badge.clock")
                            Spacer()
                            if store.isLoadingEPG { ProgressView() }
                        }
                    }
                    .disabled(store.isLoadingEPG)
                } else {
                    Text("Keine EPG-Adresse vorhanden. Du kannst sie unter „Playlists verwalten“ eintragen.")
                        .foregroundStyle(.secondary)
                }
            } header: {
                Text("TV-Guide (EPG)")
            } footer: {
                if let error = store.epgErrorMessage {
                    Text(error).foregroundStyle(.red)
                }
            }

            Section {
                Picker("Player", selection: $playerEngine) {
                    ForEach(PlayerEngine.allCases) { engine in
                        Text(engine.title).tag(engine)
                    }
                }
            } header: {
                Text("Wiedergabe")
            } footer: {
                Text(PlayerEngine.isVLCAvailable
                     ? "„Automatisch“ nutzt den Apple-Player für HLS/MP4 und VLC für Formate wie .ts oder .mkv. Scheitert der Apple-Player, wird VLC versucht."
                     : "VLC ist nicht eingebunden. Füge in Xcode das Paket „vlckit-spm“ hinzu, um .ts- und .mkv-Streams abzuspielen (siehe README).")
            }

            Section("Verlauf") {
                Button("Verlauf und Fortschritt löschen", systemImage: "clock.arrow.circlepath", role: .destructive) {
                    confirmsClearHistory = true
                }
                .disabled(userData.history.isEmpty && userData.progress.isEmpty)
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
        .confirmationDialog("Verlauf löschen?", isPresented: $confirmsClearHistory, titleVisibility: .visible) {
            Button("Löschen", role: .destructive) { userData.clearHistory() }
        } message: {
            Text("„Zuletzt gesehen“ und „Weiterschauen“ werden geleert. Favoriten bleiben erhalten.")
        }
    }
}

/// Alle Playlists: wechseln, bearbeiten, löschen, hinzufügen.
struct PlaylistsView: View {
    @Environment(PlaylistStore.self) private var store

    @State private var showsAdd = false
    @State private var editing: Playlist?
    @State private var deleting: Playlist?

    var body: some View {
        List {
            ForEach(store.playlists) { playlist in
                Button {
                    Task { await store.activate(playlist.id) }
                } label: {
                    HStack(spacing: 14) {
                        Image(systemName: playlist.source.symbol)
                            .foregroundStyle(.white)
                            .frame(width: 36, height: 36)
                            .background(Theme.gradient, in: .rect(cornerRadius: 10))
                        VStack(alignment: .leading, spacing: 2) {
                            Text(playlist.name).font(.body.weight(.medium))
                            Text("\(playlist.source.typeTitle) · \(playlist.source.displayDetail)")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                        Spacer()
                        if playlist.id == store.activeID {
                            Image(systemName: "checkmark.circle.fill")
                                .foregroundStyle(Theme.orange)
                                .accessibilityLabel("Aktiv")
                        }
                    }
                    .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .swipeActions {
                    Button("Löschen", systemImage: "trash", role: .destructive) { deleting = playlist }
                    Button("Bearbeiten", systemImage: "pencil") { editing = playlist }
                        .tint(Theme.orange)
                }
                .contextMenu {
                    Button("Bearbeiten", systemImage: "pencil") { editing = playlist }
                    Button("Löschen", systemImage: "trash", role: .destructive) { deleting = playlist }
                }
            }
        }
        .navigationTitle("Playlists")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button("Hinzufügen", systemImage: "plus") { showsAdd = true }
            }
        }
        .sheet(isPresented: $showsAdd) {
            NavigationStack { AddPlaylistView(isSheet: true) }
        }
        .sheet(item: $editing) { playlist in
            NavigationStack { PlaylistEditView(playlist: playlist) }
        }
        .confirmationDialog(
            "Playlist löschen?",
            isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
            titleVisibility: .visible,
            presenting: deleting
        ) { playlist in
            Button("„\(playlist.name)“ löschen", role: .destructive) {
                Task { await store.delete(playlist.id) }
            }
        } message: { _ in
            Text("Die Playlist und ihr Zwischenspeicher werden von diesem Gerät entfernt.")
        }
    }
}

/// Name und EPG-Adresse einer Playlist ändern.
struct PlaylistEditView: View {
    @Environment(PlaylistStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    let playlist: Playlist

    @State private var name = ""
    @State private var epgText = ""
    @State private var error: String?

    var body: some View {
        Form {
            Section("Name") {
                TextField("Name", text: $name)
            }
            Section {
                TextField("http://anbieter.de/epg.xml.gz", text: $epgText, axis: .vertical)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            } header: {
                Text("EPG-Adresse (XMLTV)")
            } footer: {
                if let detected = playlist.detectedEPGURL {
                    Text("Leer lassen, um die automatisch erkannte Adresse zu verwenden: \(detected.absoluteString)")
                } else {
                    Text("Optional. Unterstützt XMLTV-Dateien, auch gepackt (.xml.gz).")
                }
            }
            if let error {
                Section { Text(error).foregroundStyle(.red) }
            }
        }
        .navigationTitle("Playlist bearbeiten")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Abbrechen", systemImage: "xmark") { dismiss() }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("Sichern", systemImage: "checkmark", action: save)
            }
        }
        .onAppear {
            name = playlist.name
            epgText = playlist.customEPGURL?.absoluteString ?? ""
        }
    }

    private func save() {
        let trimmedEPG = epgText.trimmingCharacters(in: .whitespacesAndNewlines)
        let epgURL = trimmedEPG.isEmpty ? nil : URL.userInput(trimmedEPG)
        if !trimmedEPG.isEmpty && epgURL == nil {
            error = "Die EPG-Adresse ist ungültig."
            return
        }
        Task {
            await store.rename(playlist.id, to: name)
            if epgURL != playlist.customEPGURL {
                await store.setCustomEPGURL(epgURL, for: playlist.id)
            }
            dismiss()
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
