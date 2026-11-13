# IPTV für iOS

Eine schlanke IPTV-App für iPhone und iPad in SwiftUI. Bedienkonzept und Funktionsumfang orientieren sich an bekannten IPTV-Playern wie Hot Player: ein Startbildschirm mit **Live TV, Filmen und Serien**, Kategorien, Suche und ein nativer Player. Die App enthält keine eigenen Inhalte, sondern spielt deine M3U-Playlist ab.

## Funktionen

- **Playlist per URL laden**: Link vom IPTV-Anbieter einfügen (auch über den Einfügen-Button). Die Liste wird lokal gespeichert und steht beim nächsten Start sofort bereit, auch offline.
- **Startbildschirm** mit großen Kacheln für Live TV, Filme und Serien, dazu Playlist-Status und Aktualisieren.
- **Automatische Aufteilung** der Playlist in die drei Bereiche:
  - über das Pfadschema der Stream-URLs (`/movie/…`, `/series/…`, typisch für Xtream-Codes-Anbieter),
  - über Dateiendungen (`.mp4`, `.mkv` …),
  - über Gruppennamen, die mit „VOD“ oder „Serie“ beginnen,
  - über Episodenkennungen wie `S01 E02`.
- **Kategorien** aus `group-title` bzw. `#EXTGRP` als Kacheln mit passenden Symbolen, in jedem Bereich mit einer Kachel „Alle“.
- **Live TV** als Senderliste, **Filme** als Poster-Raster (Cover aus `tvg-logo`), **Serien** zu Staffeln und Episoden zusammengefasst.
- **Suche** als eigener Such-Tab (iOS 26) über alle Bereiche, Treffer nach Live TV, Filmen und Serien gruppiert.
- **Player** auf Basis von AVKit mit Bild-in-Bild, AirPlay und Ton im Hintergrund.
- **Einstellungen**: aktualisieren, Playlist wechseln oder entfernen.
- **Design**: Orange, Liquid Glass (`glassEffect`, `.glass`-Buttons), Tab-Leiste, die beim Scrollen kleiner wird; Hell- und Dunkelmodus.

## Technik

| Bereich | Umsetzung |
|---|---|
| Plattform | iOS / iPadOS 26+ |
| Sprache | Swift 6 (Strict Concurrency) |
| UI | SwiftUI, `Tab`-API mit `Tab(role: .search)`, Liquid Glass |
| Zustand | `@Observable` (`PlaylistStore`), Environment |
| Netzwerk | `async/await` mit `URLSession` |
| Parsing | Eigener M3U-Parser, läuft im Hintergrund |
| Speicherung | JSON-Snapshot in *Application Support* |
| Wiedergabe | `AVPlayerViewController` |

## Projektstruktur

```
IPTV.xcodeproj         Xcode-Projekt (synchronisierte Ordner: neue Dateien in IPTV/ werden automatisch übernommen)
IPTV-Info.plist        Zusatzeinträge: ATS für http-Streams, Hintergrund-Audio
IPTV/
  App/                 App-Einstieg und Farben (Theme)
  Models/              Channel, ContentKind (Klassifizierung), PlaylistContent, SeriesShow
  Services/            M3UParser, PlaylistStore
  Views/               Start, Bereiche/Kategorien, Listen, Poster, Suche, Player, Einstellungen
  Assets.xcassets      App-Icon und Akzentfarbe
Samples/beispiel.m3u   Beispiel-Playlist mit öffentlichen Test-Streams
```

## Starten

1. Projekt mit **Xcode 26** oder neuer öffnen: `IPTV.xcodeproj`.
2. Unter *Signing & Capabilities* dein Team auswählen. Die Bundle-ID ist `com.kresbach.IPTV`.
3. Simulator oder iPhone wählen und mit ⌘R starten.

Zum Ausprobieren ohne eigenen Anbieter kannst du den Raw-Link zu `Samples/beispiel.m3u` verwenden, sofern das Repository öffentlich ist. Alternativ lädst du die Datei an einer beliebigen Stelle hoch, z. B. als GitHub Gist (Raw-Link).

## Hinweise

- **Filme im MKV-Format** kann AVPlayer nicht abspielen; MP4 und HLS funktionieren.
- **Stream-Formate**: AVPlayer spielt HLS (`.m3u8`) zuverlässig ab. Reine MPEG-TS-Streams (`.ts`, oft bei Xtream-Codes-Anbietern) laufen damit häufig nicht. Viele Anbieter bieten dieselben Sender auch als HLS an, z. B. mit `output=m3u8` oder `output=hls` im Playlist-Link. Falls nötig, lässt sich später VLCKit als zweiter Player einbauen.
- **App Transport Security** ist für Medien und Playlists gelockert (`NSAllowsArbitraryLoads`), weil viele Anbieter nur `http` nutzen. Für eine Veröffentlichung im App Store musst du das gegenüber Apple begründen.
- Bitte nutze die App nur mit Inhalten, für die du die Rechte hast.
