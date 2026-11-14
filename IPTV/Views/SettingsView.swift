import AuthenticationServices
import SwiftUI

struct SettingsView: View {
    @Environment(PlaylistStore.self) private var store
    @Environment(UserDataStore.self) private var userData
    @Environment(SyncService.self) private var sync
    @Environment(DownloadManager.self) private var downloads
    @State private var confirmsDeleteDownloads = false
    @Environment(\.dismiss) private var dismiss
    @Environment(\.webAuthenticationSession) private var webAuthenticationSession
    @AppStorage(SyncedSettings.syncPlaylistsKey) private var syncPlaylists = false

    @AppStorage(PlayerEngine.liveStorageKey) private var liveEngine: PlayerEngine = PlayerEngine.liveDefault
    @AppStorage(PlayerEngine.vodStorageKey) private var vodEngine: PlayerEngine = PlayerEngine.vodDefault
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
                Picker(selection: $liveEngine) {
                    ForEach(PlayerEngine.allCases) { Text($0.title).tag($0) }
                } label: {
                    Label("Live TV", systemImage: ContentKind.live.symbol)
                }
                Picker(selection: $vodEngine) {
                    ForEach(PlayerEngine.allCases) { Text($0.title).tag($0) }
                } label: {
                    Label("Filme & Serien", systemImage: ContentKind.movie.symbol)
                }
            } header: {
                Text("Player")
            } footer: {
                Text(PlayerEngine.isVLCAvailable
                     ? "Live TV mit „Apple“: Bild-in-Bild und AirPlay. Live TV mit „Automatisch“: schnellerer Start über VLC, und beim Umschalten wird die Verbindung sofort frei – empfohlen für Konten mit nur einer Verbindung. Filme und Serien: VLC (spielt auch .mkv)."
                     : "VLC ist nicht eingebunden. Füge in Xcode das Paket „vlckit-spm“ hinzu, um .ts- und .mkv-Streams abzuspielen (siehe README).")
            }

            googleDriveSection

            downloadsSection

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
        .onChange(of: liveEngine) { SyncedSettings.markChanged() }
        .onChange(of: vodEngine) { SyncedSettings.markChanged() }
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

extension SettingsView {
    /// Speicherbelegung und Verhalten der Downloads.
    @ViewBuilder
    var downloadsSection: some View {
        @Bindable var downloads = self.downloads
        Section {
            LabeledContent("Heruntergeladen", value: "\(downloads.completedItems.count)")
            LabeledContent("Belegt", value: DownloadManager.format(downloads.usedBytes))
            LabeledContent("Frei auf dem Gerät", value: DownloadManager.format(DownloadManager.availableBytes))
            Toggle("Während der Wiedergabe pausieren", isOn: $downloads.pausesDuringPlayback)
                .tint(Theme.orange)
            Button("Alle Downloads löschen", systemImage: "trash", role: .destructive) {
                confirmsDeleteDownloads = true
            }
            .disabled(downloads.completedItems.isEmpty)
            .confirmationDialog("Alle Downloads löschen?", isPresented: $confirmsDeleteDownloads, titleVisibility: .visible) {
                Button("Löschen", role: .destructive) { downloads.removeAllCompleted() }
            }
        } header: {
            Text("Downloads")
        } footer: {
            Text("Downloads laufen auch bei gesperrtem Gerät weiter. Da viele IPTV-Konten nur eine Verbindung erlauben, pausiert ein Download, solange ein Stream läuft, und setzt danach fort.")
        }
    }

    /// Google-Drive-Synchronisierung für Favoriten, Fortschritt und Einstellungen.
    @ViewBuilder
    var googleDriveSection: some View {
        Section {
            if !sync.isConfigured {
                Label {
                    Text("Für die Synchronisierung wird eine Google-Client-ID benötigt. Trage sie in GoogleConfig.swift ein (Anleitung im README).")
                        .font(.footnote)
                } icon: {
                    Image(systemName: "info.circle.fill").foregroundStyle(Theme.orange)
                }
            } else if !sync.isSignedIn {
                Button {
                    Task { await sync.signIn(using: webAuthenticationSession, store: store, userData: userData) }
                } label: {
                    Label("Mit Google anmelden", systemImage: "person.crop.circle.badge.plus")
                }
            } else {
                if let email = sync.accountEmail {
                    LabeledContent("Konto", value: email)
                }
                LabeledContent("Letzter Abgleich") {
                    if let lastSync = sync.lastSync {
                        Text(lastSync, format: .relative(presentation: .named))
                    } else {
                        Text("noch nie")
                    }
                }
                Button {
                    Task { await sync.sync(store: store, userData: userData) }
                } label: {
                    HStack {
                        Label("Jetzt synchronisieren", systemImage: "arrow.triangle.2.circlepath")
                        Spacer()
                        if sync.isSyncing { ProgressView() }
                    }
                }
                .disabled(sync.isSyncing)

                Toggle(isOn: $syncPlaylists) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Playlists synchronisieren")
                        Text("Inklusive Links und Zugangsdaten")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .tint(Theme.orange)

                Button("Abmelden", systemImage: "rectangle.portrait.and.arrow.right", role: .destructive) {
                    sync.signOut()
                }
            }
        } header: {
            Text("Google Drive")
        } footer: {
            if let error = sync.lastError {
                Text(error).foregroundStyle(.red)
            } else {
                Text("Favoriten, „Weiterschauen“, „Zuletzt gesehen“ und Player-Einstellungen werden in einem versteckten App-Ordner deines Google Drive gespeichert und auf allen Geräten abgeglichen. Die App hat keinen Zugriff auf deine übrigen Dateien.")
            }
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
