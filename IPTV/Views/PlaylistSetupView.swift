import SwiftUI

/// Eingabe der Playlist-Adresse. Wird beim ersten Start gezeigt und
/// in den Einstellungen zum Wechseln der Playlist wiederverwendet.
struct PlaylistSetupView: View {
    @Environment(PlaylistStore.self) private var store
    @Environment(\.dismiss) private var dismiss

    /// `true`, wenn die Ansicht als Sheet aus den Einstellungen geöffnet wurde.
    var isSheet = false

    @State private var urlText = ""
    @FocusState private var isFieldFocused: Bool

    var body: some View {
        ScrollView {
            VStack(spacing: 28) {
                header

                VStack(alignment: .leading, spacing: 12) {
                    Text("Playlist-Adresse")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.secondary)

                    HStack(spacing: 12) {
                        Image(systemName: "link")
                            .foregroundStyle(Theme.orange)
                        TextField("https://anbieter.de/playlist.m3u", text: $urlText)
                            .textContentType(.URL)
                            .keyboardType(.URL)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .submitLabel(.go)
                            .focused($isFieldFocused)
                            .onSubmit(load)
                        if !urlText.isEmpty {
                            Button("Löschen", systemImage: "xmark.circle.fill") { urlText = "" }
                                .labelStyle(.iconOnly)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .padding(16)
                    .glassEffect(.regular.interactive(), in: .rect(cornerRadius: 20))

                    PasteButton(payloadType: String.self) { strings in
                        if let first = strings.first { urlText = first }
                    }
                    .buttonBorderShape(.capsule)
                    .labelStyle(.titleAndIcon)
                }

                if let error = store.errorMessage {
                    Label(error, systemImage: "exclamationmark.triangle.fill")
                        .font(.callout)
                        .foregroundStyle(.red)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .transition(.opacity)
                }

                Button(action: load) {
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
                .disabled(urlText.trimmingCharacters(in: .whitespaces).isEmpty || store.isLoading)

                Text("Die Adresse bekommst du von deinem IPTV-Anbieter. Unterstützt werden M3U- und M3U8-Listen.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding(24)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
            .animation(.smooth, value: store.errorMessage)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(Theme.backgroundGlow.ignoresSafeArea())
        .toolbar {
            if isSheet {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Abbrechen", systemImage: "xmark") { dismiss() }
                }
            }
        }
        .onAppear {
            store.errorMessage = nil
            if isSheet, let current = store.sourceURL { urlText = current.absoluteString }
        }
    }

    private var header: some View {
        VStack(spacing: 16) {
            Image(systemName: "play.tv.fill")
                .font(.system(size: 44, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 96, height: 96)
                .background(Theme.gradient, in: .rect(cornerRadius: 28))
                .shadow(color: Theme.orange.opacity(0.45), radius: 20, y: 10)

            VStack(spacing: 6) {
                Text(isSheet ? "Playlist wechseln" : "Willkommen")
                    .font(.largeTitle.bold())
                Text("Füge den Link zu deiner M3U-Playlist ein, um deine Sender zu laden.")
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
        .padding(.top, isSheet ? 8 : 48)
    }

    private func load() {
        isFieldFocused = false
        Task {
            if await store.load(from: urlText), isSheet {
                dismiss()
            }
        }
    }
}

#Preview {
    PlaylistSetupView()
        .environment(PlaylistStore())
        .tint(Theme.orange)
}
