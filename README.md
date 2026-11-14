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

## Hinweise

- **http-Playlists**: Die `IPTV-Info.plist` erlaubt http über `NSAllowsArbitraryLoads`. Ergänze dort keine weiteren `NSAllows…`-Schlüssel, sonst ignoriert iOS diese Freigabe und http-Downloads scheitern.
- **User-Agent**: Viele IPTV-Server lassen nur bekannte Player zu. Die App meldet sich daher als VLC.
- **App Store**: Die gelockerte App Transport Security musst du gegenüber Apple begründen.
- Bitte nutze die App nur mit Inhalten, für die du die Rechte hast.
