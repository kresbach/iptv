import XCTest

/// Automatische Oberflächen-Tests. Starten in Xcode mit ⌘U (iPhone-Simulator).
/// Die App läuft dabei im Testmodus mit einer eingebauten Beispiel-Playlist.
/// Tests, die Videos abspielen, brauchen eine Internetverbindung.
@MainActor
final class IPTVUITests: XCTestCase {

    private var app: XCUIApplication!

    override func setUp() async throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments += ["-UITestSampleData", "-AppleLanguages", "(de)", "-AppleLocale", "de_DE"]
        app.launch()
        // Warten, bis die Beispiel-Playlist geladen ist und der Startbildschirm steht.
        XCTAssertTrue(tab("Live TV").waitForExistence(timeout: 20), "Startbildschirm erscheint nicht")
    }

    // MARK: - Tests

    func testStartbildschirmZeigtBereiche() {
        XCTAssertTrue(tab("Start").exists)
        XCTAssertTrue(tab("Filme").exists)
        XCTAssertTrue(tab("Serien").exists)
        XCTAssertTrue(app.staticTexts["Live TV"].firstMatch.exists)
    }

    func testLiveSenderOeffnenUndSchliessen() {
        tab("Live TV").tap()
        openCategory("Alle")
        button(containing: "Apple BipBop").tap()

        // Player ist offen (nicht abgestürzt) und lässt sich schließen.
        let close = app.buttons["Schließen"]
        XCTAssertTrue(close.waitForExistence(timeout: 10), "Player öffnet nicht")
        XCTAssertEqual(app.state, .runningForeground, "App ist abgestürzt")
        sleep(3)
        revealPlayerControls()
        close.tap()
        XCTAssertTrue(tab("Live TV").waitForExistence(timeout: 5))
    }

    func testSenderImPlayerUmschalten() {
        tab("Live TV").tap()
        openCategory("Alle")
        button(containing: "Apple BipBop").tap()
        XCTAssertTrue(app.buttons["Schließen"].waitForExistence(timeout: 10))

        revealPlayerControls()
        let next = app.buttons["Nächster Sender"]
        XCTAssertTrue(next.waitForExistence(timeout: 5), "Taste „Nächster Sender“ fehlt")
        next.tap()
        XCTAssertEqual(app.state, .runningForeground, "App ist beim Umschalten abgestürzt")

        revealPlayerControls()
        app.buttons["Schließen"].tap()
    }

    func testFilmDetailansicht() {
        tab("Filme").tap()
        openCategory("Alle")
        button(containing: "Big Buck Bunny").tap()
        XCTAssertTrue(button(containing: "Abspielen").waitForExistence(timeout: 10), "Detailansicht fehlt")
        XCTAssertTrue(button(containing: "Herunterladen").exists, "Download-Knopf fehlt")
    }

    func testSerieZeigtEpisoden() {
        tab("Serien").tap()
        openCategory("Alle")
        button(containing: "Kurzclips").tap()
        XCTAssertTrue(app.staticTexts["Episode 1"].waitForExistence(timeout: 10), "Episoden fehlen")
    }

    func testSucheFindetSender() {
        tab("Suche").tap()
        let field = app.searchFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        field.typeText("Mux")
        XCTAssertTrue(button(containing: "Mux Test").waitForExistence(timeout: 5), "Suche findet den Sender nicht")
    }

    func testEinstellungenOeffnen() {
        tab("Start").tap()
        app.buttons["Einstellungen"].tap()
        XCTAssertTrue(app.navigationBars["Einstellungen"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Kindersicherung"].exists || app.buttons["PIN festlegen"].exists)
        app.buttons["Fertig"].tap()
    }

    func testKategorieAusblenden() {
        tab("Live TV").tap()
        XCTAssertTrue(button(containing: "Test-Sender").waitForExistence(timeout: 5))
        app.buttons["Bearbeiten"].tap()
        app.buttons["Ausblenden"].firstMatch.tap()
        app.buttons["Fertig"].tap()
        XCTAssertFalse(button(containing: "Test-Sender").waitForExistence(timeout: 2), "Kategorie wurde nicht ausgeblendet")

        // Zurücksetzen für andere Tests.
        app.buttons["Bearbeiten"].tap()
        app.buttons["Einblenden"].firstMatch.tap()
        app.buttons["Fertig"].tap()
    }

    // MARK: - Hilfen

    /// Bereich in Tab-Leiste (iPhone) oder Seitenleiste (iPad).
    private func tab(_ name: String) -> XCUIElement {
        let inTabBar = app.tabBars.buttons[name]
        return inTabBar.exists ? inTabBar : app.buttons[name].firstMatch
    }

    private func button(containing text: String) -> XCUIElement {
        app.buttons.containing(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }

    private func openCategory(_ name: String) {
        let row = button(containing: name)
        XCTAssertTrue(row.waitForExistence(timeout: 10), "Kategorie „\(name)“ fehlt")
        row.tap()
    }

    /// Tippt ins Bild, falls die Steuerung bereits ausgeblendet ist.
    private func revealPlayerControls() {
        if !app.buttons["Schließen"].isHittable {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
            _ = app.buttons["Schließen"].waitForExistence(timeout: 3)
        }
    }
}
