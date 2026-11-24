# IPTV für iOS

Eine schlanke IPTV-App für iPhone und iPad in SwiftUI. Bedienkonzept und Funktionsumfang orientieren sich an bekannten IPTV-Playern wie Hot Player: ein Startbildschirm mit **Live TV, Filmen und Serien**, Kategorien, Suche, TV-Guide und ein nativer Player. Die App enthält keine eigenen Inhalte, sondern spielt deine Playlist ab.

## Funktionen

- **Mehrere Playlists**, jeweils als
  - **M3U-Link** (http oder https; fehlt `http://`, wird es ergänzt),
  - **Xtream-Codes-Login** (Server, Benutzername, Passwort) mit Covern, Serien inkl. Staffeln und automatischem TV-Guide,
  - **M3U-Datei** aus der Dateien-App, iCloud Drive usw.
  
  Wechseln geht über das Titelmenü auf dem Startbildschirm oder unter *Einstellungen → Playlists verwalten*.
- **Startbildschirm** mit Kacheln für Live TV, Filme und Serien sowie den Leisten *Weiterschauen*, *Zuletzt gesehen* und *Favoriten*.
- **Automatische Aufteilung** von M3U-Listen in Live TV, Filme und Serien (über URL-Pfad wie `/movie/` oder `/series/`, Dateiendung, Gruppenname, `S01 E02`).
- **Favoriten**: Wischen nach links oder langes Drücken in Listen, Herz im Player und in der Serienansicht. In jedem Bereich gibt es eine Kachel *Favoriten*.
- **Weiterschauen**: Filme und Episoden starten dort, wo du aufgehört hast. Der Fortschritt erscheint als Balken.
- **TV-Guide (EPG)**: aktuelle Sendung mit Fortschritt in der Senderliste, *Jetzt/Danach* im Player, Wochenprogramm über langes Drücken → *TV-Programm*. Quellen sind XMLTV (auch `.xml.gz`), `url-tvg` aus der M3U, Xtream `xmltv.php` oder eine eigene Adresse.
- **Suche** über alle Bereiche.
- **Sortieren** von Filmen und Serien nach Neu, A–Z, Bewertung, Jahr (Filme auch nach Größe), Filter *Nur ★ 7+*, Bewertung auf den Postern (Xtream).
- **Favoriten in eigener Reihenfolge** (Live TV → Favoriten → *Bearbeiten*), auch beim Google-Sync. Live TV öffnet auf Wunsch direkt mit den Favoriten.
- **Senderliste** mit Restzeit („noch 12 Min.“) und nächster Sendung.
- **Kindersicherung**: Kategorien wie „XXX“, „Adult“ oder „18+“ werden automatisch mit der PIN gesperrt.
- **Player-Menü „…“**: Bildformat *Original / Zoom / Strecken* (Apple und VLC), **Sleep-Timer** (mit Warnung und +15 Min.), *Sendernummer eingeben*, *Zurück zum letzten Sender*. Mit Tastatur: Ziffern für die Sendernummer, ⌫ für den letzten Sender.
- **Player-Einstellungen**: Live-Puffer (Schnell/Normal/Groß), Halbbilder umwandeln für 1080i-Sender (VLC, Yadif 2x), bevorzugte Tonspur- und Untertitelsprache; Ton-Versatz im Player-Menü „…“ (VLC).
- **Archiv / verpasste Sendungen** (Xtream-Timeshift, M3U `catchup-days`): im TV-Raster bis 24 Std. zurück (Symbol ↺), *Verpasste Sendungen* im Senderprogramm und im Player-Menü, *Sendung von Anfang an*.
- **TMDB** (optional, kostenloser Schlüssel unter *Einstellungen → Trends & Empfehlungen*): Trends von Netflix, Prime Video & Co. auf der Startseite, *Das könnte dir auch gefallen* auf Detailseiten, Trailer, wenn der Anbieter keinen liefert. Ohne Schlüssel entstehen Trends aus passend benannten Kategorien.
- **Wiedergabe-Info** im Player-Menü „…“: Bilder/s, verlorene Bilder, Datenrate, Format und Einschätzung.
- **Bildspeicher** für Logos und Cover begrenzen und leeren (*Einstellungen → Bildspeicher*).
- **Player**: Apple-Player (Bild-in-Bild, AirPlay) und optional **VLC** für `.ts`, `.mkv`, `rtmp` usw. In der Einstellung *Automatisch* wird VLC verwendet, wenn das Format es erfordert oder der Apple-Player scheitert.

## Technik

| Bereich | Umsetzung |
|---|---|
| Plattform | iOS / iPadOS 26+ |
| Sprache | Swift 6 (Strict Concurrency) |
| UI | SwiftUI, `Tab`-API mit Such-Tab, Liquid Glass |
| Zustand | `@Observable` (`PlaylistStore`, `UserDataStore`) |
| Netzwerk | `async/await`, `URLSession` mit VLC-User-Agent |
| Speicherung | JSON-Dateien in *Application Support* (Dateischutz aktiv) |
| Wiedergabe | `AVPlayerViewController`, optional VLCKit |

## Projektstruktur

```
IPTV.xcodeproj         Xcode-Projekt (synchronisierte Ordner)
IPTV-Info.plist        ATS für http, Heimnetz-Zugriff, Hintergrund-Audio
IPTV/
  App/                 App-Einstieg und Farben
  Models/              Channel, Playlist, PlaylistContent, SeriesShow, EPG, Klassifizierung
  Services/            M3UParser, XtreamClient, EPGService, PlaylistStore, UserDataStore, Storage
  Player/              PlayerScreen, Apple-Player, VLC-Player (optional)
  Views/               Start, Bereiche, Listen, Poster, Suche, Einstellungen, Playlist hinzufügen
Samples/beispiel.m3u   Beispiel-Playlist mit öffentlichen Test-Streams
```

## Starten

1. `IPTV.xcodeproj` mit **Xcode 26** öffnen.
2. Unter *Signing & Capabilities* dein Team wählen.
3. Simulator oder iPhone wählen und mit ⌘R starten.

## VLC einbinden (optional, empfohlen für .ts/.mkv)

Der Apple-Player spielt HLS (`.m3u8`) und MP4. Für MPEG-TS (`.ts`), MKV und andere Formate braucht es VLC. Der Code dafür ist schon vorhanden und wird automatisch aktiv, sobald das Paket eingebunden ist:

1. In Xcode **File → Add Package Dependencies …**
2. Oben rechts die Adresse `https://github.com/tylerjonesio/vlckit-spm` eingeben.
3. *Dependency Rule*: „Up to Next Major Version“ ab `3.6.0`, dann **Add Package**.
4. Das Produkt **VLCKitSPM** dem Target **IPTV** zuordnen und **Add Package** bestätigen.
5. Neu bauen (⌘R). In den Einstellungen erscheint unter *Wiedergabe* jetzt VLC als Option.

Hinweis: `vlckit-spm` ist ein inoffizielles Paket, das die offiziellen VLCKit-Binärdateien von VideoLAN für den Swift Package Manager bereitstellt. Es vergrößert die App um etwa 50–70 MB.

## Google-Drive-Synchronisierung einrichten

Favoriten, „Weiterschauen“, „Zuletzt gesehen“ und die Player-Einstellungen (optional auch die Playlists) lassen sich über Google Drive zwischen Geräten abgleichen. Gespeichert wird in einem **versteckten App-Ordner** (`appDataFolder`), auf andere Dateien hat die App keinen Zugriff.

Einmalig eine eigene Google-Client-ID anlegen (kostenlos, ca. 5 Minuten):

1. [console.cloud.google.com](https://console.cloud.google.com) öffnen und ein **neues Projekt** anlegen, z. B. „IPTV“.
2. **APIs & Dienste → Bibliothek**: „Google Drive API“ suchen und **aktivieren**.
3. **APIs & Dienste → OAuth-Zustimmungsbildschirm** (bzw. „Google Auth Platform“):
   - Nutzertyp **Extern**, App-Name „IPTV“, deine E-Mail als Support- und Entwickler-Kontakt.
   - Unter **Zielgruppe / Testnutzer** deine Google-Adresse eintragen. Im Testmodus können sich nur eingetragene Testnutzer anmelden, das genügt für die private Nutzung.
4. **APIs & Dienste → Anmeldedaten → Anmeldedaten erstellen → OAuth-Client-ID**:
   - Anwendungstyp **iOS**
   - Bundle-ID: `com.kresbach.IPTV`
5. Die angezeigte **Client-ID** (endet auf `.apps.googleusercontent.com`) in `IPTV/App/GoogleConfig.swift` bei `clientID` eintragen.
6. App neu bauen → *Einstellungen → Google Drive → Mit Google anmelden*.

Beim ersten Anmelden warnt Google ggf., dass die App nicht verifiziert ist: über **Erweitert → Weiter zu IPTV** bestätigen.

Abgleich: beim App-Start, beim Wechsel in den Hintergrund, ca. 20 Sekunden nach Änderungen und per *Jetzt synchronisieren*. Bei Konflikten gewinnt je Eintrag die neuere Änderung; entfernte Favoriten tauchen nicht wieder auf.

## TestFlight (mit Apple-Entwicklerkonto)

Mit einem Apple-Entwicklerkonto (99 €/Jahr) installierst du die App über die **TestFlight**-App, ohne Kabel und ohne 7-Tage-Grenze. Jede hochgeladene Version läuft 90 Tage.

**Einmalig einrichten**
1. **Xcode → Settings → Accounts**: mit **+** die Apple-ID des Entwicklerkontos hinzufügen.
2. **Team-ID** notieren: developer.apple.com → Account → *Membership details* (10 Zeichen, z. B. `AB12CD34EF`).
3. **App anlegen** auf [appstoreconnect.apple.com](https://appstoreconnect.apple.com) → *Apps* → **+** → *Neue App*:
   Plattform iOS, Name z. B. „IPTV“ (muss im App Store eindeutig sein, sonst ergänzen), Sprache Deutsch,
   **Bundle-ID `com.kresbach.IPTV`** (falls nicht auswählbar: unter developer.apple.com → *Identifiers* anlegen), SKU beliebig (z. B. `iptv`).

**Hochladen**
```
cd ~/Developer/iptv
git pull
./scripts/upload-testflight.sh DEINE_TEAM_ID
```

**Installieren**
1. In App Store Connect bei der App → **TestFlight** → *Interne Tests* → Gruppe anlegen und dich hinzufügen (beim ersten Build wird ggf. nach der Exportkontrolle gefragt: „Keine“).
2. Auf dem iPhone die App **TestFlight** aus dem App Store laden, Einladung annehmen, **IPTV installieren**.

Neue Versionen: Skript erneut ausführen; TestFlight meldet das Update.

## Dauerhaft installieren ohne Bezahlkonto (SideStore)

Mit einer kostenlosen Apple-ID läuft eine selbst installierte App nur 7 Tage. **SideStore** erneuert diese Signatur automatisch auf dem iPhone, ohne Mac und ohne Kabel.

1. **SideStore einrichten** (einmalig): Anleitung auf [sidestore.io](https://sidestore.io). Du brauchst dafür einmal einen Mac oder PC.
2. **IPA bauen** auf dem Mac im Projektordner:
   ```
   git pull
   ./scripts/build-ipa.sh
   ```
   Ergebnis: `build/IPTV.ipa`
3. **Installieren:** Die IPA per AirDrop aufs iPhone schicken, „Öffnen mit SideStore“ wählen und mit deiner Apple-ID anmelden.
4. **Erneuern:** SideStore frischt die App automatisch im Hintergrund auf. Öffne SideStore spätestens alle 7 Tage einmal, falls die automatische Erneuerung nicht greift.

Grenzen der kostenlosen Apple-ID: höchstens **3 selbst installierte Apps** gleichzeitig (SideStore zählt mit) und höchstens 10 neue App-IDs pro Woche.

## Hinweise

- **http-Playlists**: Die `IPTV-Info.plist` erlaubt http über `NSAllowsArbitraryLoads`. Ergänze dort keine weiteren `NSAllows…`-Schlüssel, sonst ignoriert iOS diese Freigabe und http-Downloads scheitern.
- **User-Agent**: Viele IPTV-Server lassen nur bekannte Player zu. Die App meldet sich daher als VLC.
- **App Store**: Die gelockerte App Transport Security musst du gegenüber Apple begründen.
- Bitte nutze die App nur mit Inhalten, für die du die Rechte hast.
