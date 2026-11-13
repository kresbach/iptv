# IPTV für iOS

Eine schlanke IPTV-App für iPhone und iPad in SwiftUI. Du lädst eine M3U-Playlist über einen Link, die App zeigt die Sender nach Kategorien geordnet an, durchsucht sie und spielt sie im nativen Player ab.

## Funktionen

- **Playlist per URL laden**: Link vom IPTV-Anbieter einfügen (auch über den Einfügen-Button). Die Liste wird lokal gespeichert und steht beim nächsten Start sofort bereit, auch offline.
- **Kategorien** aus `group-title` bzw. `#EXTGRP` als Kacheln mit passenden Symbolen.
- **Alle Sender** als Liste, mit Filter und Pull-to-Refresh.
- **Suche** als eigener Such-Tab (iOS 26) über Sendername und Kategorie, mit kurzer Verzögerung beim Tippen, damit auch große Listen flüssig bleiben.
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
  Models/              Channel, ChannelCategory
  Services/            M3UParser, PlaylistStore
  Views/               Onboarding, Kategorien, Listen, Suche, Player, Einstellungen
  Assets.xcassets      App-Icon und Akzentfarbe
Samples/beispiel.m3u   Beispiel-Playlist mit öffentlichen Test-Streams
```

## Starten

1. Projekt mit **Xcode 26** oder neuer öffnen: `IPTV.xcodeproj`.
2. Unter *Signing & Capabilities* dein Team auswählen. Die Bundle-ID ist `com.kresbach.IPTV`.
3. Simulator oder iPhone wählen und mit ⌘R starten.

Zum Ausprobieren ohne eigenen Anbieter kannst du den Raw-Link zu `Samples/beispiel.m3u` verwenden, sofern das Repository öffentlich ist. Alternativ lädst du die Datei an einer beliebigen Stelle hoch, z. B. als GitHub Gist (Raw-Link).

## Hinweise

- **Stream-Formate**: AVPlayer spielt HLS (`.m3u8`) zuverlässig ab. Reine MPEG-TS-Streams (`.ts`, oft bei Xtream-Codes-Anbietern) laufen damit häufig nicht. Viele Anbieter bieten dieselben Sender auch als HLS an, z. B. mit `output=m3u8` oder `output=hls` im Playlist-Link. Falls nötig, lässt sich später VLCKit als zweiter Player einbauen.
- **App Transport Security** ist für Medien und Playlists gelockert (`NSAllowsArbitraryLoads`), weil viele Anbieter nur `http` nutzen. Für eine Veröffentlichung im App Store musst du das gegenüber Apple begründen.
- Bitte nutze die App nur mit Inhalten, für die du die Rechte hast.
