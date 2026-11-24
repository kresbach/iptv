# Projektstand (Übergabe für neue Sitzungen)

Kurzüberblick, damit eine neue Claude-Sitzung ohne den langen Verlauf weiterarbeiten kann.
Antworten an den Nutzer auf Deutsch, kurz und verständlich (kein Entwickler).

## Repository
- Branch: `claude/loving-maxwell-iaygiz` (GitHub `kresbach/iptv`)
- `IPTV/` – iOS/iPadOS-App (SwiftUI, iOS 26, Swift 6), läuft auch auf dem Mac als „Designed for iPad“
- `IPTVUITests/` – XCUITests; Testmodus über Startparameter `-UITestSampleData` (nur Simulator)
- `android/` – Google-TV-App (Kotlin, Compose for TV), wird in einer eigenen Sitzung gepflegt; APK unter `android/release/`
- `scripts/build-ipa.sh` – unsignierte IPA für SideStore; `scripts/upload-testflight.sh TEAM_ID` – TestFlight
- Nutzer baut lokal in `~/Developer/iptv` (nicht OneDrive/Downloads)

## Wichtige Entscheidungen / Erfahrungen
- Xtream-Konto des Nutzers erlaubt **nur 1 Verbindung**. Live-HLS hält sie beim Server 20–60 s; MPEG-TS gibt sofort frei.
  Kein Vorab-Check von `active_cons` (veraltet) – sofort starten, bei Fehler bis zu 10× neu versuchen.
- Player: Live standardmäßig Apple (AVPlayer, PiP/AirPlay), Filme/Serien VLC (`vlckit-spm`, im Projekt eingetragen).
  Einstellungen getrennt (`PlayerEngine.liveStorageKey`/`vodStorageKey`); „Automatisch“ = Live über VLC mit `.ts`.
- Gemeinsame Daten als Singletons (`PlaylistStore.shared`, `UserDataStore.shared`, `SyncService.shared`,
  `DownloadManager.shared`, `CategoryPreferences.shared`, `AppRouter.shared`, `PlaybackCoordinator.shared`);
  Views greifen direkt darauf zu (`var store: PlaylistStore { .shared }`), **nicht** über `@Environment` (führte auf dem Mac zu Abstürzen).
- Google-Drive-Sync (Ordner „IPTV“, Scope drive.file – appDataFolder nur noch zum einmaligen Übernehmen alter Daten; OAuth PKCE, Client-ID in `App/GoogleConfig.swift`); Zugangsdaten in Links werden vor dem Upload geschwärzt.
- OpenSubtitles: API-Key/Zugangsdaten im Schlüsselbund, nur mit VLC.
- Verkleinern von Downloads (MP4/MKV) wurde ausprobiert und **wieder entfernt** – nicht erneut anbieten.
- ⌘N ist auf dem Mac/iPad „Neues Fenster“ (Playlist hinzufügen = ⇧⌘N).
- Kein Mac in der Cloud-Umgebung: iOS-Code wird hier ohne Compiler geschrieben; Remote-Control-Verbindung zum Mac kam nie zustande.
- Installation beim Nutzer: kostenlose Apple-ID + **SideStore** (LocalDevVPN, Pairing-Datei über iloader; im Firmennetz scheitert es wegen TLS-Prüfung).
  Firmen-Entwicklerkonto ohne Admin-Rechte – TestFlight dort nicht möglich.

## Versionierung
Bei **jeder Änderungsrunde** `MARKETING_VERSION` des App-Ziels in `IPTV.xcodeproj/project.pbxproj`
erhöhen (beide Stellen: Debug und Release) und einen Abschnitt in `CHANGELOG.md` ergänzen.
Die Build-Nummer setzt `scripts/build-ipa.sh` automatisch (Anzahl der Commits). Aktuell: 1.4.

## Funktionen (iOS)
Playlists (M3U-Link inkl. get.php→Xtream, Datei, Xtream), Live/Filme/Serien, Kategorien (sortieren/ausblenden/PIN),
Favoriten, Weiterschauen, EPG (XMLTV gestreamt, Zeitraster), Suche, Film-/Seriendetails mit Trailer und weiteren Fassungen,
Größe/Sortierung, Downloads (Hintergrund, Speicherlimit), Untertitel (eingebettet + OpenSubtitles), Player mit Gesten,
Umschalten, nächster Episode, AirPlay, Seitenleiste/Menüs/Tastaturkürzel/Player-Fenster auf Mac/iPad.
Übernommen aus Android (ungetestet gebaut, ohne Compiler): Sortierung Neu/A–Z/Bewertung/Jahr + „Nur ★ 7+“
(`Views/MediaSort.swift`, Bewertung/Datum aus Xtream in `Channel.rating/added`), Favoriten-Reihenfolge inkl. Sync
(`UserDataStore.moveFavorites`, `SyncSnapshot.favoriteOrder`), Live TV startet mit Favoriten, Restzeit + nächste Sendung
in der Senderliste, Erwachsenen-Kategorien automatisch sperren (`CategoryPreferences.autoLocksAdult`),
Player-Menü „…“ mit Bildformat (`VideoFit`), Sleep-Timer, Sendernummer, letzter Sender, Bildspeicher-Grenze.
Zweite Runde (ebenfalls ohne Compiler): Live-Puffer/Deinterlacing/Sprachen (`Player/PlaybackSettings.swift`),
Ton-Versatz (VLC), Archiv (`Services/Catchup.swift`, `Channel.archiveDays`, `PlaylistData.serverTimeZone`,
EPG behält für Archiv-Sender ältere Sendungen), TMDB (`Services/TMDB.swift`, `Trends.swift`, `Similar.swift`).

## Offene Ideen
- Native macOS-App (dauerhaft ohne Konto nutzbar) – am besten in einer Sitzung direkt auf dem Mac bauen.
- Erinnerungen, neue Folgen, Binär-Zwischenspeicher, Sicherung, Siri/Widget, „Sport jetzt live“, Chromecast
  (1.4: Wiedergabe-Info `PlaybackStats`, VLC-Fläche wiederverwenden `VLCSurfaceHolder`)
  (vollständige Liste der Android-Funktionen, die iOS noch fehlen: siehe Verlauf bzw. android/README.md).
