import SwiftUI

/// Kategorien eines Bereichs sortieren, ausblenden und (mit PIN) sperren.
struct CategoryEditView: View {
    private var prefs: CategoryPreferences { .shared }
    @Environment(\.dismiss) private var dismiss

    let kind: ContentKind
    let categories: [ChannelCategory]

    var body: some View {
        let arranged = prefs.arranged(categories, kind: kind, includeHidden: true)

        List {
            Section {
                ForEach(arranged) { category in
                    HStack(spacing: 12) {
                        Text(category.name)
                            .foregroundStyle(prefs.isHidden(kind, category.name) ? .secondary : .primary)
                            .lineLimit(2)
                        Spacer(minLength: 8)

                        if prefs.hasPIN {
                            Button {
                                prefs.setLocked(!prefs.isLocked(kind, category.name), kind: kind, name: category.name)
                            } label: {
                                Image(systemName: prefs.isLocked(kind, category.name) ? "lock.fill" : "lock.open")
                                    .foregroundStyle(prefs.isLocked(kind, category.name) ? Theme.orange : .secondary)
                            }
                            .buttonStyle(.borderless)
                            .accessibilityLabel(prefs.isLocked(kind, category.name) ? "Sperre aufheben" : "Mit PIN sperren")
                        }

                        Button {
                            prefs.setHidden(!prefs.isHidden(kind, category.name), kind: kind, name: category.name)
                        } label: {
                            Image(systemName: prefs.isHidden(kind, category.name) ? "eye.slash" : "eye")
                                .foregroundStyle(prefs.isHidden(kind, category.name) ? .secondary : Theme.orange)
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel(prefs.isHidden(kind, category.name) ? "Einblenden" : "Ausblenden")
                    }
                }
                .onMove { source, destination in
                    prefs.move(kind: kind, current: arranged, from: source, to: destination)
                }
            } footer: {
                Text(prefs.hasPIN
                     ? "Ziehe Kategorien am Griff, um sie zu sortieren. Das Auge blendet aus, das Schloss sperrt mit der PIN."
                     : "Ziehe Kategorien am Griff, um sie zu sortieren. Das Auge blendet aus. Zum Sperren lege in den Einstellungen unter „Kindersicherung“ eine PIN fest.")
            }

            Section {
                Button("Reihenfolge zurücksetzen", systemImage: "arrow.uturn.backward") {
                    prefs.resetOrder(kind: kind)
                }
            }
        }
        .environment(\.editMode, .constant(.active))
        .navigationTitle("\(kind.title) bearbeiten")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Fertig", systemImage: "checkmark") { dismiss() }
            }
        }
    }
}

/// Fragt die Kindersicherungs-PIN ab und gibt bei Erfolg die Sitzung frei.
struct PINPrompt: ViewModifier {
    private var prefs: CategoryPreferences { .shared }
    @Binding var isPresented: Bool
    let onSuccess: () -> Void

    @State private var pin = ""
    @State private var wasWrong = false

    func body(content: Content) -> some View {
        content.alert(wasWrong ? "Falsche PIN" : "PIN eingeben", isPresented: $isPresented) {
            SecureField("PIN", text: $pin)
                .keyboardType(.numberPad)
            Button("Entsperren") {
                let entered = pin
                pin = ""
                if prefs.unlock(with: entered) {
                    wasWrong = false
                    onSuccess()
                } else {
                    wasWrong = true
                    // Nach dem Schließen erneut fragen.
                    Task { @MainActor in
                        try? await Task.sleep(for: .milliseconds(350))
                        isPresented = true
                    }
                }
            }
            Button("Abbrechen", role: .cancel) {
                pin = ""
                wasWrong = false
            }
        } message: {
            Text("Diese Kategorie ist durch die Kindersicherung geschützt.")
        }
    }
}

extension View {
    func pinPrompt(isPresented: Binding<Bool>, onSuccess: @escaping () -> Void = {}) -> some View {
        modifier(PINPrompt(isPresented: isPresented, onSuccess: onSuccess))
    }
}

/// PIN festlegen oder ändern.
struct PINSetupView: View {
    private var prefs: CategoryPreferences { .shared }
    @Environment(\.dismiss) private var dismiss

    @State private var pin = ""
    @State private var confirmation = ""

    private var isValid: Bool {
        pin.count >= 4 && pin.allSatisfy(\.isNumber) && pin == confirmation
    }

    var body: some View {
        Form {
            Section {
                SecureField("Neue PIN (mindestens 4 Ziffern)", text: $pin)
                    .keyboardType(.numberPad)
                SecureField("PIN wiederholen", text: $confirmation)
                    .keyboardType(.numberPad)
            } footer: {
                Text("Mit der PIN sperrst du einzelne Kategorien. Gesperrte Inhalte erscheinen auch nicht in der Suche. Merke dir die PIN gut: Ohne sie lassen sich die Sperren nur durch Neuinstallieren der App entfernen.")
            }
        }
        .navigationTitle("PIN festlegen")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Abbrechen", systemImage: "xmark") { dismiss() }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("Sichern", systemImage: "checkmark") {
                    prefs.setPIN(pin)
                    dismiss()
                }
                .disabled(!isValid)
            }
        }
    }
}
