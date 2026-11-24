import AuthenticationServices
import SwiftUI

struct SettingsView: View {
    private var store: PlaylistStore { .shared }
    private var userData: UserDataStore { .shared }
    private var sync: SyncService { .shared }
    private var downloads: DownloadManager { .shared }
    @State private var confirmsDeleteDownloads = false
    private var prefs: CategoryPreferences { .shared }
    @State private var showsPINSetup = false
    @State private var subtitleKey = ""
    @State private var subtitleUser = ""
    @State private var subtitlePassword = ""
    @State private var subtitleLanguages = ""
    @State private var asksPIN = false
    @State private var pinAction: PINAction = .change

    private enum PINAction { case change, remove }
    @AppStorage(DownloadSettings.storageLimitKey) private var storageLimitGB = 0
    @AppStorage(DownloadSettings.autoDeleteKey) private var autoDeleteWatched = false
    @Environment(\.dismiss) private var dismiss
    @Environment(\.webAuthenticationSession) private var webAuthenticationSession
    @AppStorage(SyncedSettings.syncPlaylistsKey) private var syncPlaylists = false

    @AppStorage(PlayerEngine.liveStorageKey) private var liveEngine: PlayerEngine = PlayerEngine.liveDefault
    @AppStorage(PlayerEngine.vodStorageKey) private var vodEngine: PlayerEngine = PlayerEngine.vodDefault
    @State private var confirmsClearHistory = false
    @AppStorage(ImageCacheSettings.limitKey) private var imageCacheLimitMB = ImageCacheSettings.defaultLimitMB
    @AppStorage(LiveSettings.startsWithFavoritesKey) private var liveStartsWithFavorites = true
    @AppStorage(LiveBuffer.storageKey) private var liveBuffer: LiveBuffer = .normal
    @AppStorage(DeinterlaceMode.storageKey) private var deinterlace: DeinterlaceMode = .automatic
    @AppStorage(TrackLanguages.audioKey) private var audioLanguage = ""
    @AppStorage(TrackLanguages.subtitleKey) private var subtitleLanguage = ""
    @State private var imageCacheUsed = ImageCacheSettings.usedBytes
    @State private var tmdbKey = ""

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
                    PlaylistsView().appEnvironment()
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
                Toggle(isOn: $liveStartsWithFavorites) {
                    Label("Live TV mit Favoriten öffnen", systemImage: "heart")
                }
                Picker(selection: $liveBuffer) {
                    ForEach(LiveBuffer.allCases) { Text($0.title).tag($0) }
                } label: {
                    Label("Live-Puffer", systemImage: "gauge.with.dots.needle.33percent")
                }
                Picker(selection: $deinterlace) {
                    ForEach(DeinterlaceMode.allCases) { Text($0.title).tag($0) }
                } label: {
                    Label("Halbbilder umwandeln", systemImage: "square.stack.3d.down.right")
                }
                Picker(selection: $audioLanguage) {
                    Text("Automatisch").tag("")
                    ForEach(TrackLanguages.choices, id: \.code) { Text($0.title).tag($0.code) }
                } label: {
                    Label("Tonspur", systemImage: "speaker.wave.2")
                }
                Picker(selection: $subtitleLanguage) {
                    Text("Aus").tag("")
                    ForEach(TrackLanguages.choices, id: \.code) { Text($0.title).tag($0.code) }
                } label: {
                    Label("Untertitel", systemImage: "captions.bubble")
                }
            } header: {
                Text("Player")
            } footer: {
                Text(PlayerEngine.isVLCAvailable
                     ? "Live-Puffer: „Schnell“ schaltet zügiger um, „Groß“ hilft bei Aussetzern. Halbbilder umwandeln (nur VLC) macht Bewegungen bei 1080i-Sendern wie Fußball flüssig. Tonspur und Untertitel werden automatisch in der gewählten Sprache eingestellt, sofern vorhanden. Live TV mit „Apple“: Bild-in-Bild und AirPlay. Live TV mit „Automatisch“: schnellerer Start über VLC, und beim Umschalten wird die Verbindung sofort frei – empfohlen für Konten mit nur einer Verbindung. Filme und Serien: VLC (spielt auch .mkv)."
                     : "VLC ist nicht eingebunden. Füge in Xcode das Paket „vlckit-spm“ hinzu, um .ts- und .mkv-Streams abzuspielen (siehe README).")
            }

            googleDriveSection

            downloadsSection

            subtitleSection

            tmdbSection

            parentalSection

            imageCacheSection

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
    /// Online-Untertitel über OpenSubtitles.
    @ViewBuilder
    var subtitleSection: some View {
        Section {
            SecureField("API-Key", text: $subtitleKey)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .onChange(of: subtitleKey) { _, value in SubtitleService.shared.apiKey = value }
            TextField("Sprachen, z. B. de,en,tr", text: $subtitleLanguages)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .onChange(of: subtitleLanguages) { _, value in SubtitleService.shared.languages = value }
            TextField("Benutzername (optional)", text: $subtitleUser)
                .textContentType(.username)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .onChange(of: subtitleUser) { _, value in SubtitleService.shared.username = value }
            SecureField("Passwort (optional)", text: $subtitlePassword)
                .textContentType(.password)
                .onChange(of: subtitlePassword) { _, value in SubtitleService.shared.password = value }
        } header: {
            Text("Untertitel (OpenSubtitles)")
        } footer: {
            Text("Den kostenlosen API-Key erhältst du auf opensubtitles.com unter „API Consumers“. Ohne Anmeldung sind etwa 5 Downloads pro Tag möglich, mit Benutzername und Passwort etwa 20. Untertitel suchst du im Player über das Sprechblasen-Symbol (nur mit VLC).")
        }
        .onAppear {
            let service = SubtitleService.shared
            subtitleKey = service.apiKey
            subtitleLanguages = service.languages
            subtitleUser = service.username
            subtitlePassword = service.password
        }
    }

    /// Kindersicherung: PIN festlegen, ändern oder entfernen.
    @ViewBuilder
    var parentalSection: some View {
        Section {
            if prefs.hasPIN {
                LabeledContent("Status", value: prefs.isUnlocked ? "Entsperrt" : "Aktiv")
                Button("PIN ändern", systemImage: "key") {
                    pinAction = .change
                    if prefs.isUnlocked { showsPINSetup = true } else { asksPIN = true }
                }
                Button("Kindersicherung ausschalten", systemImage: "lock.open", role: .destructive) {
                    pinAction = .remove
                    if prefs.isUnlocked { prefs.setPIN(nil) } else { asksPIN = true }
                }
                if prefs.isUnlocked {
                    Button("Jetzt wieder sperren", systemImage: "lock") { prefs.lock() }
                }
            } else {
                Button("PIN festlegen", systemImage: "lock") { showsPINSetup = true }
            }
            Toggle(isOn: Binding(get: { prefs.autoLocksAdult }, set: { prefs.autoLocksAdult = $0 })) {
                Label("Erwachsenen-Kategorien sperren", systemImage: "eye.slash")
            }
            // Ausschalten nur ohne PIN oder nach der Eingabe.
            .disabled(prefs.hasPIN && !prefs.isUnlocked)
        } header: {
            Text("Kindersicherung")
        } footer: {
            Text("Mit einer PIN sperrst du einzelne Kategorien unter Live TV, Filme oder Serien über „Bearbeiten“. Kategorien wie „XXX“, „Adult“ oder „18+“ werden automatisch gesperrt, sobald eine PIN festgelegt ist. Gesperrte Inhalte erscheinen weder in Listen noch in der Suche, bis die PIN eingegeben wurde. Beim Verlassen der App wird wieder gesperrt.")
        }
        .sheet(isPresented: $showsPINSetup) {
            NavigationStack { PINSetupView() }.appEnvironment()
        }
        .pinPrompt(isPresented: $asksPIN) {
            switch pinAction {
            case .change: showsPINSetup = true
            case .remove: prefs.setPIN(nil)
            }
        }
    }

    /// TMDB für Trends, Empfehlungen und fehlende Trailer.
    @ViewBuilder
    var tmdbSection: some View {
        let trends = TrendsStore.shared
        Section {
            SecureField("API-Schlüssel oder Token", text: $tmdbKey)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .onChange(of: tmdbKey) { _, value in TMDB.setKey(value) }
            Button {
                Task { await trends.refresh(force: true) }
            } label: {
                HStack {
                    Label("Trends aktualisieren", systemImage: "flame")
                    Spacer()
                    if trends.isLoading { ProgressView() }
                }
            }
            .disabled(tmdbKey.trimmingCharacters(in: .whitespaces).isEmpty || trends.isLoading)
            if let fetchedAt = trends.fetchedAt {
                LabeledContent("Stand") {
                    Text(fetchedAt, format: .relative(presentation: .named))
                }
            }
        } header: {
            Text("Trends & Empfehlungen (TMDB)")
        } footer: {
            VStack(alignment: .leading, spacing: 4) {
                if let error = trends.lastError {
                    Text(error).foregroundStyle(.red)
                }
                Text("Mit einem kostenlosen Schlüssel von themoviedb.org (Einstellungen → API) zeigt die Startseite, was bei Netflix, Prime Video & Co. gerade beliebt ist, die Detailseiten zeigen passende Empfehlungen und Trailer, wenn der Anbieter keinen liefert. Ohne Schlüssel werden Trends aus passend benannten Kategorien deines Anbieters gebildet. Aktualisiert wird alle 12 Stunden.")
            }
        }
        .onAppear { tmdbKey = TMDB.key }
    }

    /// Zwischenspeicher für Senderlogos und Cover.
    @ViewBuilder
    var imageCacheSection: some View {
        Section {
            Picker(selection: $imageCacheLimitMB) {
                ForEach(ImageCacheSettings.choices, id: \.self) { mb in
                    Text(mb >= 1000 ? "\(mb / 1000) GB" : "\(mb) MB").tag(mb)
                }
            } label: {
                Label("Höchstens", systemImage: "photo.stack")
            }
            .onChange(of: imageCacheLimitMB) { _, value in
                ImageCacheSettings.apply(limitMB: value)
                imageCacheUsed = ImageCacheSettings.usedBytes
            }
            LabeledContent("Belegt", value: Int64(imageCacheUsed).formatted(.byteCount(style: .file)))
            Button("Bildspeicher leeren", systemImage: "trash") {
                ImagePipeline.shared.clearAll()
                imageCacheUsed = ImageCacheSettings.usedBytes
            }
            .disabled(imageCacheUsed == 0)
        } header: {
            Text("Bildspeicher")
        } footer: {
            Text("Logos und Cover werden auf dem Gerät zwischengespeichert, damit Listen schnell laden. Beim Leeren werden sie bei Bedarf neu geladen.")
        }
        .onAppear { imageCacheUsed = ImageCacheSettings.usedBytes }
    }

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
            Picker("Speicherlimit", selection: $storageLimitGB) {
                ForEach(DownloadSettings.storageLimitOptions, id: \.self) { gigabytes in
                    Text(gigabytes == 0 ? "Kein Limit" : "\(gigabytes) GB").tag(gigabytes)
                }
            }
            Toggle("Gesehene automatisch löschen", isOn: $autoDeleteWatched)
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
            Text("Downloads laufen auch bei gesperrtem Gerät weiter und pausieren, solange ein Stream läuft. Dateien werden in Originalqualität gespeichert.")
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
                Text("Favoriten, „Weiterschauen“, „Zuletzt gesehen“ und Player-Einstellungen werden im Ordner „IPTV“ deines Google Drive gespeichert und auf allen Geräten (auch dem Fernseher) abgeglichen. Die App sieht nur die Dateien, die sie selbst angelegt hat.")
            }
        }
    }
}

/// Alle Playlists: wechseln, bearbeiten, löschen, hinzufügen.
struct PlaylistsView: View {
    private var store: PlaylistStore { .shared }

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
            NavigationStack { AddPlaylistView(isSheet: true) }.appEnvironment()
        }
        .sheet(item: $editing) { playlist in
            NavigationStack { PlaylistEditView(playlist: playlist) }.appEnvironment()
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
    private var store: PlaylistStore { .shared }
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
        return "\(version) (Build \(build))"
    }
}
