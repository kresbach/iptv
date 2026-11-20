# IPTV für Google TV / Android TV

Gegenstück zur iOS-App (Ordner `IPTV/`) für Fernseher mit Google TV, z. B. Sony Bravia.
Bedienung komplett mit der Fernbedienung (Steuerkreuz, OK, Zurück).

## Fertige App herunterladen

Die fertige Test-Version liegt hier im Repository:

```
android/release/IPTV-TV-debug.apk
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
   `https://github.com/kresbach/iptv/raw/claude/loving-maxwell-iaygiz/android/release/IPTV-TV-debug.apk`
   (Ist das Repository privat, funktioniert der Link nicht direkt. Dann die APK am PC
   herunterladen und z. B. per USB-Stick oder Weg B übertragen.)
4. Nach dem Download auf **Installieren** gehen. Die App erscheint danach in der App-Leiste als **IPTV**.

### Weg B: per ADB über WLAN (vom PC aus)

1. Auf dem PC die **Android Platform Tools** installieren (enthält `adb`).
2. Am Fernseher die IP-Adresse nachsehen: *Einstellungen → Netzwerk & Internet → (dein WLAN)*.
3. Am PC:
   ```
   adb connect 192.168.1.50:5555
   adb install -r IPTV-TV-debug.apk
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

## Selbst bauen

Voraussetzungen: JDK 17 oder neuer, Android SDK (Platform 36, Build-Tools).

```
cd android
echo "sdk.dir=/pfad/zum/android-sdk" > local.properties
./gradlew assembleDebug        # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # Unit-Tests (Parser, Xtream, TV-Guide)
```

## Noch nicht enthalten (spätere Stufen)

- Google-Drive-Sync mit der iOS-App
- Kindersicherung
- TV-Programm als Raster (EPG-Grid)
- Audio-/Untertitelspur-Auswahl, M3U-Datei-Import
