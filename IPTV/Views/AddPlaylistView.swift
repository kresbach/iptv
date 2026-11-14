import SwiftUI
import UniformTypeIdentifiers

/// Neue Playlist hinzufügen: per M3U-Link, Xtream-Codes-Login oder Datei aus der Dateien-App.
/// Wird beim ersten Start gezeigt und später als Sheet wiederverwendet.
struct AddPlaylistView: View {
    @Environment(PlaylistStore.self) private var store
    @Environment(\.dismiss) private var dismiss

    var isSheet = false

    enum Method: String, CaseIterable, Identifiable {
        case link = "Link"
        case xtream = "Xtream"
        case file = "Datei"
        var id: String { rawValue }
    }

    @State private var method: Method = .link
    @State private var name = ""
    @State private var urlText = ""
    @State private var server = ""
    @State private var username = ""
    @State private var password = ""
    @State private var fileName: String?
    @State private var fileData: Data?
    @State private var showsFileImporter = false
    @State private var localError: String?

    var body: some View {
        Form {
            Section {
                header
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets())
            }

            Section {
                Picker("Art", selection: $method) {
                    ForEach(Method.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets())
            }

            switch method {
            case .link: linkSection
            case .xtream: xtreamSection
            case .file: fileSection
            }

            Section {
                TextField("Name (optional)", text: $name)
            } footer: {
                Text("Unter diesem Namen erscheint die Playlist in der App.")
            }

            if let error = localError ?? store.errorMessage {
                Section {
                    Label(error, systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.red)
                }
            }

            Section {
                Button(action: submit) {
                    Group {
                        if store.isLoading {
                            ProgressView().tint(.white)
                        } else {
                            Label("Playlist laden", systemImage: "arrow.down.circle.fill")
                        }
                    }
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
                }
                .buttonStyle(.glassProminent)
                .controlSize(.large)
                .disabled(!canSubmit || store.isLoading)
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets())
            }
        }
        .scrollContentBackground(.hidden)
        .background(Theme.backgroundGlow.ignoresSafeArea())
        .navigationTitle(isSheet ? "Playlist hinzufügen" : "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if isSheet {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Abbrechen", systemImage: "xmark") { dismiss() }
                }
            }
        }
        .fileImporter(
            isPresented: $showsFileImporter,
            allowedContentTypes: [.m3uPlaylist, UTType(filenameExtension: "m3u8") ?? .plainText, .plainText, .data]
        ) { result in
            importFile(result)
        }
        .onAppear { store.errorMessage = nil }
        .onChange(of: method) { localError = nil; store.errorMessage = nil }
    }

    // MARK: - Abschnitte

    private var header: some View {
        VStack(spacing: 14) {
            Image(systemName: "play.tv.fill")
                .font(.system(size: 40, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 88, height: 88)
                .background(Theme.gradient, in: .rect(cornerRadius: 26))
                .shadow(color: Theme.orange.opacity(0.45), radius: 18, y: 8)

            Text(isSheet ? "Neue Playlist" : "Willkommen")
                .font(.largeTitle.bold())
            Text("Füge deine Playlist per Link, mit Xtream-Zugangsdaten oder als Datei hinzu.")
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, isSheet ? 4 : 24)
    }

    private var linkSection: some View {
        Section {
            TextField("http://anbieter.de/get.php?…", text: $urlText, axis: .vertical)
                .textContentType(.URL)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            PasteButton(payloadType: String.self) { strings in
                if let first = strings.first { urlText = first }
            }
        } header: {
            Text("M3U-Link")
        } footer: {
            Text("http- und https-Adressen werden unterstützt, auch Panel-Links wie „…/get.php?username=…&password=…“. Fehlt „http://“, wird es automatisch ergänzt.")
        }
    }

    private var xtreamSection: some View {
        Section {
            TextField("Server, z. B. http://server.de:8080", text: $server)
                .textContentType(.URL)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            TextField("Benutzername", text: $username)
                .textContentType(.username)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            SecureField("Passwort", text: $password)
                .textContentType(.password)
        } header: {
            Text("Xtream Codes")
        } footer: {
            Text("Mit Xtream-Zugang gibt es Cover, Serien mit Staffeln und den TV-Guide automatisch.")
        }
    }

    private var fileSection: some View {
        Section {
            Button {
                showsFileImporter = true
            } label: {
                Label(fileName ?? "Datei auswählen …", systemImage: fileName == nil ? "folder" : "doc.fill")
            }
        } header: {
            Text("M3U-Datei")
        } footer: {
            Text("Wähle eine .m3u- oder .m3u8-Datei vom iPhone, aus iCloud Drive oder einem anderen Speicher.")
        }
    }

    // MARK: - Aktionen

    private var canSubmit: Bool {
        switch method {
        case .link: !urlText.trimmingCharacters(in: .whitespaces).isEmpty
        case .xtream: !server.isEmpty && !username.isEmpty && !password.isEmpty
        case .file: fileData != nil
        }
    }

    private func submit() {
        localError = nil
        let source: PlaylistSource
        let defaultName: String

        switch method {
        case .link:
            guard let url = URL.userInput(urlText) else {
                localError = "Bitte gib eine gültige Adresse ein, z. B. http://anbieter.de/playlist.m3u"
                return
            }
            let linkName = name.trimmingCharacters(in: .whitespaces).isEmpty ? (url.host() ?? "Playlist") : name
            if let credentials = url.xtreamCredentials {
                // Panel-Link (get.php): zuerst als Xtream laden (Cover, Serien, TV-Guide),
                // klappt das nicht, als M3U-Liste.
                Task {
                    var success = await store.addPlaylist(name: linkName, source: .xtream(credentials))
                    if !success {
                        success = await store.addPlaylist(name: linkName, source: .m3u(url: url.completedPanelPlaylistURL))
                    }
                    if success, isSheet { dismiss() }
                }
                return
            }
            source = .m3u(url: url)
            defaultName = url.host() ?? "Playlist"
        case .xtream:
            guard let serverURL = XtreamClient.normalizedServer(server) else {
                localError = "Bitte gib eine gültige Serveradresse ein, z. B. http://server.de:8080"
                return
            }
            source = .xtream(XtreamCredentials(
                server: serverURL,
                username: username.trimmingCharacters(in: .whitespaces),
                password: password
            ))
            defaultName = serverURL.host() ?? "Xtream"
        case .file:
            guard fileData != nil else { return }
            let file = fileName ?? "Playlist.m3u"
            source = .file(originalName: file)
            defaultName = (file as NSString).deletingPathExtension
        }

        let finalName = name.trimmingCharacters(in: .whitespaces).isEmpty ? defaultName : name
        Task {
            if await store.addPlaylist(name: finalName, source: source, fileData: method == .file ? fileData : nil), isSheet {
                dismiss()
            }
        }
    }

    private func importFile(_ result: Result<URL, Error>) {
        switch result {
        case .success(let url):
            let accessing = url.startAccessingSecurityScopedResource()
            defer { if accessing { url.stopAccessingSecurityScopedResource() } }
            do {
                fileData = try Data(contentsOf: url)
                fileName = url.lastPathComponent
                localError = nil
            } catch {
                localError = "Die Datei konnte nicht gelesen werden: \(error.localizedDescription)"
            }
        case .failure(let error):
            localError = error.localizedDescription
        }
    }
}

#Preview {
    NavigationStack {
        AddPlaylistView()
    }
    .environment(PlaylistStore())
    .tint(Theme.orange)
}
