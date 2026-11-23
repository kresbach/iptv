# IPTV für Google TV / Android TV

Gegenstück zur iOS-App (Ordner `IPTV/`) für Fernseher mit Google TV, z. B. Sony Bravia.
Bedienung komplett mit der Fernbedienung (Steuerkreuz, OK, Zurück).

## Fertige App herunterladen

Die fertige Test-Version liegt hier im Repository:

```
android/release/IPTV-TV.apk
```

## Installation auf dem Sony-Fernseher

### 1. Entwickleroptionen einschalten (einmalig)

1. **Einstellungen → System → Info** öffnen
   (je nach Modell: *Einstellungen → Geräteeinstellungen → Info*).
2. Ganz nach unten zu **Android TV-Betriebssystem-Build** gehen.
3. **7-mal OK drücken**, bis „Du bist jetzt Entwickler“ erscheint.
4. Unter **Einstellungen → System → Entwickleroptionen** jetzt bei Bedarf
   **USB-Debugging** bzw. **ADB-Debugging über WLAN** einschalten (nur für Weg B).

### Weg A: mit der App „Downloader“ (am einfachsten)

1. Im Google Play Store auf dem Fernseher die App **Downloader** (von AFTVnews) installieren.
2. **Einstellungen → Apps → Sicherheit & Einschränkungen → Unbekannte Quellen**:
   für **Downloader** erlauben.
3. In Downloader die Adresse der APK eingeben, z. B.
   `https://github.com/kresbach/iptv/raw/claude/loving-maxwell-iaygiz/android/release/IPTV-TV.apk`
   (Ist das Repository privat, funktioniert der Link nicht direkt. Dann die APK am PC
   herunterladen und z. B. per USB-Stick oder Weg B übertragen.)
4. Nach dem Download auf **Installieren** gehen. Die App erscheint danach in der App-Leiste als **IPTV**.

### Weg B: per ADB über WLAN (vom PC aus)

1. Auf dem PC die **Android Platform Tools** installieren (enthält `adb`).
2. Am Fernseher die IP-Adresse nachsehen: *Einstellungen → Netzwerk & Internet → (dein WLAN)*.
3. Am PC:
   ```
   adb connect 192.168.1.50:5555
   adb install -r IPTV-TV.apk
   ```
   Beim ersten Verbinden fragt der Fernseher, ob der PC zugreifen darf → **Erlauben**.
   Bei neueren Geräten (Android 11+) ggf. **Kabelloses Debugging → Gerät koppeln** verwenden und
   vorher `adb pair IP:PORT` mit dem angezeigten Code ausführen.

## Playlist bequem per Handy eintragen

Auf dem Fernseher **Playlists → Playlist hinzufügen** öffnen (bzw. beim ersten Start
„Playlist hinzufügen“). Rechts erscheint ein **QR-Code**:

1. Handy ins **selbe WLAN** wie den Fernseher.
2. QR-Code mit der Handy-Kamera scannen (oder die angezeigte Adresse im Handy-Browser öffnen).
3. Link einfügen (oder auf „Xtream-Login“ tippen und Server/Benutzer/Passwort eingeben).
4. **An Fernseher senden** – der Fernseher lädt die Playlist sofort, das Handy zeigt an, wann sie fertig ist.

Über das Handy lassen sich auch eine **M3U-Datei** hochladen und eine eigene **TV-Guide-Adresse** eintragen.

Die Eingabeseite ist nur erreichbar, solange „Playlist hinzufügen“ auf dem Fernseher offen ist,
und nur mit dem Zufalls-Code aus dem QR-Code.

## Suche

Auf der Startseite die Kachel **Suche** wählen. Durchsucht Live TV, Filme und Serien gleichzeitig.
Groß-/Kleinschreibung und Akzente sind egal („pokemon“ findet „Pokémon“), mehrere Wörter dürfen
in beliebiger Reihenfolge stehen. Tipp: Auf der Bildschirmtastatur gibt es meist ein Mikrofon
für die Spracheingabe.

## Bedienung

| Wo | Taste | Wirkung |
|---|---|---|
| überall | Zurück | eine Ebene zurück |
| Listen/Kacheln | OK **lange drücken** | Favorit hinzufügen/entfernen |
| Live-Player | ▲ / ▼ (oder CH+/CH−) | Sender wechseln |
| Live-Player | OK | Info zur aktuellen Sendung ein/aus |
| Live-Player | ◀ | Senderliste einblenden |
| Film/Serie | OK | Steuerung zeigen, dann Pause/Weiter |
| Film/Serie | ◀ / ▶ | 10 s zurück / 30 s vor |

## Funktionen (Stufe 1)

- Playlist per **M3U-Link** oder **Xtream-Codes-Login** (Server, Benutzer, Passwort).
  Links mit `get.php?username=…&password=…` werden automatisch als Xtream erkannt.
- Alles wird lokal gespeichert, die App startet sofort; Sender, Filme, Serien und TV-Guide
  werden beim Start im Hintergrund aktualisiert (nie während ein Stream läuft).
- Startbildschirm mit Reihen: Bereiche, Zuletzt gesehen, Favoriten, Live TV, neue Filme, neue Serien.
- **Live TV**: Kategorien links, Sender rechts mit aktueller Sendung (XMLTV-TV-Guide, auch `.gz`).
- **Filme/Serien**: Raster mit Covern, Detailseite mit Handlung (Xtream `get_vod_info` /
  `get_series_info`), Staffeln und Episoden, **Weiterschauen** an der letzten Position,
  nächste Folge startet automatisch.

### Wichtig bei nur einer erlaubten Verbindung

- Live-Sender von Xtream werden als **MPEG-TS** abgespielt (gibt die Verbindung beim Server sofort
  frei; HLS blockiert sie 20–60 s). Nur wenn der Anbieter TS nicht erlaubt, wird HLS genutzt.
- Beim Senderwechsel wird der alte Player vollständig beendet, bevor der neue startet.
  Schnelles Durchzappen verbindet erst, wenn man 0,5 s auf einem Sender bleibt.
- Bricht der Stream ab oder meldet der Server „zu viele Verbindungen“, verbindet die App
  automatisch neu (mit kurzer, wachsender Pause; ggf. Wechsel TS ↔ HLS bzw. Player).
- Drückt man die Home-Taste oder geht der Fernseher in Standby, wird die Verbindung sofort getrennt.
- Alle Anfragen nutzen den User-Agent von VLC, da viele Anbieter andere Apps sperren.

### Player

- **ExoPlayer** (Media3) für Live (TS/HLS), MP4 und MKV.
- **VLC** (libVLC) für AVI, WMV, FLV, MPG usw. und automatisch als Ersatz, wenn ExoPlayer einen
  Film nicht dekodieren kann. Daher ist die APK rund 58 MB groß (nur ARM-Geräte, wie alle Google-TV-Fernseher).

## Neu in Version 2.12

- **Verpasste Sendungen (Archiv)**: Bietet der Anbieter für einen Sender ein Archiv an (Xtream „tv_archive“
  bzw. `catchup-days` in der M3U), lassen sich vergangene Sendungen abspielen und spulen:
  im **TV-Programm** (mit ↺ markiert, OK = abspielen) oder im Player-Menü (OK lange drücken):
  „Sendung von Anfang an“ und „Verpasste Sendungen“. Archiv-Sendungen landen nicht in „Weiterschauen“.
- **App-Updates**: Die App sucht alle 12 Stunden nach einer neuen Version (aus diesem GitHub-Projekt,
  `android/release/version.json`). Dann erscheint auf der Startseite die Kachel „Update“ – OK lädt und
  installiert. Beim ersten Mal fragt Android, ob die App Apps installieren darf: erlauben. Auch unter
  Einstellungen → App-Update.
- **„Weiterschauen“ auf dem Google-TV-Startbildschirm**: angefangene Filme und Episoden erscheinen dort
  (abschaltbar unter Einstellungen → Google TV). Ob Google TV die Reihe zeigt, hängt vom Fernseher ab.
- **Sleep-Timer** im Player-Menü (15–120 Min.). Eine Minute vorher erscheint ein Hinweis, OK verlängert um 30 Min.
- **Bevorzugte Tonspur und Untertitel** unter Einstellungen → Wiedergabe (Standard: Deutsch).
- **Erinnerungen**: im TV-Programm OK auf einer künftigen Sendung (⏰) oder im Player-Menü
  „An nächste Sendung erinnern“. Kurz vor Beginn erscheint ein Hinweis: OK = einschalten,
  Zurück = ausblenden. Funktioniert, solange die App geöffnet ist.
- **Neue Folgen**: Bei gemerkten Serien erscheint auf der Startseite die Reihe „Neue Folgen“,
  sobald der Anbieter Episoden ergänzt (Prüfung höchstens alle 6 Stunden).
- **Profile** (Einstellungen → Profile): eigene Favoriten, eigener Verlauf und „Weiterschauen“ je Profil.
  Mit mehreren Profilen fragt die App beim Start „Wer schaut?“. Im **Kinderprofil** sind gesperrte und
  Erwachsenen-Kategorien immer ausgeblendet, die Einstellungen und der Wechsel in ein anderes Profil
  brauchen die PIN. Der Abgleich mit dem iPhone betrifft nur das erste Profil.

## Live TV bequemer (seit Version 2.11)

- **Sendernummer eintippen**: Im Player mit den Zifferntasten der Fernbedienung direkt zu einem Sender
  springen (die Nummer steht in der Senderliste). Die Taste „Letzter Sender“ bzw. im Menü
  (OK lange drücken) „Zurück zum letzten Sender“ wechselt zum vorherigen Sender.
- **Puffer (Live TV)** unter Einstellungen → Wiedergabe: *Schnell* (Sender kommen schneller, ca. 1,5 s),
  *Normal* oder *Groß* (für wackelige Verbindungen).
- **Senderliste** zeigt die laufende Sendung, die Restzeit („noch 23 Min.“) und was danach kommt.
- **Favoriten sortieren**: Lieblingssender nach oben (Live TV → „Favoriten sortieren“ oder Einstellungen).
  Live TV startet mit den Favoriten (abschaltbar).
- **Kindersicherung**: Erwachsenen-Kategorien („XXX“, „Adult“, „18+“ …) werden bei aktiver PIN
  automatisch gesperrt – auch neue. „Kategorien bearbeiten“ ist dann ebenfalls durch die PIN geschützt.
- **Bildspeicher** (Logos, Cover) unter Einstellungen → Speicher begrenzen oder leeren.

## Startseite und Trends

- **Weiterschauen**: angefangene Filme und Episoden. **Zuletzt gesehen**: nur Live-Sender.
- **Trends**: je Streamingdienst (Netflix, Prime Video, Disney+, Apple TV+, Paramount+, Sky/WOW, RTL+,
  Joyn, MagentaTV) und „Trends in Deutschland“ – was gerade beliebt ist, aber nur Titel aus der eigenen
  Playlist. Quelle: TMDB (themoviedb.org). Kostenlosen Schlüssel holen: auf themoviedb.org registrieren →
  Einstellungen → API → API-Schlüssel anfordern; dann am Fernseher **Einstellungen → Trends** (per QR-Code
  vom Handy). Ohne Schlüssel: neueste Titel aus passend benannten Kategorien des Anbieters
  („NETFLIX“, „AMAZON PRIME“ …). Die Listen werden alle 12 Stunden aktualisiert.

### Schneller Start (seit Version 2.10)

- Die Playlist wird in einem eigenen, kompakten Format gespeichert: etwa 3–4× schneller geladen als
  vorher und rund 60 % kleiner. Beim ersten Start nach dem Update wird sie einmal umgewandelt.
- Die Startseite erscheint sofort; Kacheln und Reihen füllen sich, sobald die Playlist geladen ist.
  Der TV-Guide und die Trends werden erst danach im Hintergrund geladen bzw. berechnet.

## Ruckeln beim Live-TV

- **Bildwiederholrate anpassen** (Einstellungen, Standard: an): Deutsches TV hat 50 Bilder/s.
  Läuft der Fernseher mit 60 Hz, wird regelmäßig ein Bild doppelt gezeigt → Ruckeln. Die App schaltet
  den Fernseher beim Abspielen auf die passende Frequenz (50 Hz, 24 Hz, 60 Hz). Beim Umschalten kann
  das Bild kurz schwarz werden. Am Sony zusätzlich möglich: *Einstellungen → Bild → Erweiterte
  Einstellungen → Motionflow / „Bildfrequenz anpassen“*.
- **Puffer** Normal/Groß: Groß hilft bei wackeliger Verbindung (dafür langsameres Umschalten).
- **Tunneling**: Bild-Ton-Synchronisierung durch die Hardware; auf manchen Fernsehern flüssiger.
- **Deinterlacing für Live TV (1080i-Sender)**: Viele HD-Sender (RTL, ProSieben, Sky …) senden 1080i
  (Halbbilder). ExoPlayer macht daraus nur 25 Bilder/s → abgehackte Bewegungen. Mit dieser Einstellung
  laufen Live-Sender über VLC, das die Halbbilder zu 50 flüssigen Bildern zusammensetzt
  („Beste Qualität“ = Yadif 2x, „Schonend“ = Bob für schwächere Geräte). 720p/1080p bleiben unverändert.
  Alternative: **Tunneling** einschalten – dann übernimmt die Bild-Hardware des Fernsehers das Deinterlacing.
- **Wiedergabe-Info** im Player (OK lange drücken): Bildrate, Bildschirmfrequenz, verlorene Bilder,
  Hänger und Puffer – zeigt, woran es liegt.

## Selbst bauen

Voraussetzungen: JDK 17 oder neuer, Android SDK (Platform 36, Build-Tools).

```
cd android
echo "sdk.dir=/pfad/zum/android-sdk" > local.properties
./gradlew assembleRelease      # optimierte APK: app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest    # Unit-Tests (Parser, Xtream, TV-Guide) + TV-Simulation
```

Die **TV-Simulation** (`TvSimulationTest`, Robolectric) startet die echte Oberfläche in
1920×1080 mit Beispieldaten, bedient sie wie mit der Fernbedienung (Steuerkreuz, OK, langes OK,
Zurück, Zifferntasten) und legt Bildschirmfotos unter `app/build/tv-screens/` ab.
Videos werden dabei nicht abgespielt – das lässt sich nur auf einem echten Gerät prüfen.

## Weitere Funktionen (seit Version 2.0)

- **Optionen im Player** (OK lange drücken, bei Filmen auch ▼): Tonspur, Untertitel,
  Untertitel-Suche (OpenSubtitles), Player wählen (Automatisch / ExoPlayer / VLC),
  **Verbindung prüfen** (Kontostatus, belegte Verbindungen, Ablaufdatum, Antwort des Servers),
  Favorit.
- **TV-Programm als Raster** (Kachel „TV-Programm“): ◀ ▶ blättert durch die Zeit, OK auf einer
  laufenden Sendung schaltet um.
- **Weiterschauen** als eigene Reihe auf der Startseite.
- **Kategorien bearbeiten** (Einstellungen): ausblenden, sortieren, mit PIN sperren.
- **Kindersicherung**: PIN festlegen; gesperrte Kategorien sind erst nach PIN-Eingabe sichtbar
  (in der Kategorienliste „🔒 Gesperrte freischalten“), bis die App verlassen wird.
- **Filme**: Trailer (YouTube-App), weitere Fassungen desselben Films (SD/HD/4K).
- **Downloads** von Filmen und Episoden zum Offline-Schauen, mit Speicherlimit,
  „Gesehene automatisch löschen“ und Pause während der Wiedergabe (eine Verbindung).
  Downloads laufen, solange die App geöffnet ist.
- **Playlists bearbeiten** (Name, Zugang, eigene TV-Guide-Adresse) und **M3U-Datei** vom Handy hochladen.
- **Verlauf und Fortschritt löschen** (Einstellungen).
- **Google-Drive-Sync** mit der iPhone-App (Favoriten, Weiterschauen, Verlauf,
  Player-Einstellungen, optional Playlists) – dieselbe Datei im versteckten App-Ordner.

### Google-Drive-Sync einrichten (einmalig)

Der Fernseher meldet sich wie YouTube per Code an. Dafür braucht es einen zweiten
OAuth-Client im **selben Google-Cloud-Projekt** wie die iPhone-App:

1. [console.cloud.google.com](https://console.cloud.google.com) → Projekt der iPhone-App öffnen.
2. **APIs & Dienste → Anmeldedaten → Anmeldedaten erstellen → OAuth-Client-ID**.
3. Anwendungstyp **„Fernseher und Geräte mit begrenzter Eingabe“**, Name z. B. „IPTV TV“.
4. Am Fernseher: **Einstellungen → Google Drive → Google-Zugang einrichten**, QR-Code mit dem
   Handy scannen und Client-ID sowie Clientschlüssel senden.
5. **Mit Google anmelden**: Der Fernseher zeigt einen Code, den du am Handy unter
   google.com/device eingibst (dasselbe Google-Konto wie auf dem iPhone).

### Untertitel (OpenSubtitles) einrichten

Kostenlosen API-Key auf opensubtitles.com unter „API Consumers“ anlegen, dann
**Einstellungen → Untertitel** (geht auch per QR-Code vom Handy). Suchen im Player:
OK lange drücken → „Untertitel suchen“.
