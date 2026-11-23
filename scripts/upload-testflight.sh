#!/bin/bash
# Baut die iOS-App und lädt sie zu TestFlight (App Store Connect) hoch.
#
# Voraussetzungen (einmalig, siehe README „TestFlight“):
#   - Xcode → Settings → Accounts: Apple-ID des Entwicklerkontos hinzugefügt
#   - App in App Store Connect angelegt (Bundle-ID siehe unten)
#
# Aufruf im Projektordner auf dem Mac:
#   ./scripts/upload-testflight.sh DEINE_TEAM_ID
# Die Team-ID (10 Zeichen) steht auf developer.apple.com unter „Membership details“.
set -euo pipefail

TEAM_ID="${1:-}"
if [ -z "$TEAM_ID" ]; then
  echo "Aufruf: ./scripts/upload-testflight.sh DEINE_TEAM_ID"
  echo "Die Team-ID findest du auf developer.apple.com → Account → Membership details."
  exit 1
fi

cd "$(dirname "$0")/.."

# Vollständiges Xcode verwenden, auch wenn nur die Command Line Tools aktiv sind.
if ! xcodebuild -version >/dev/null 2>&1; then
  XCODE_APP=$(ls -d /Applications/Xcode*.app 2>/dev/null | sort | tail -1 || true)
  [ -n "${XCODE_APP:-}" ] || { echo "✗ Xcode nicht gefunden."; exit 1; }
  export DEVELOPER_DIR="$XCODE_APP/Contents/Developer"
fi

BUILD_DIR="$PWD/build"
ARCHIVE="$BUILD_DIR/IPTV.xcarchive"
# Jede Version braucht eine neue Build-Nummer: Datum und Uhrzeit.
BUILD_NUMBER=$(date +%Y%m%d%H%M)

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"

cat > "$BUILD_DIR/ExportOptions.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>method</key>
	<string>app-store-connect</string>
	<key>destination</key>
	<string>upload</string>
	<key>teamID</key>
	<string>$TEAM_ID</string>
	<key>signingStyle</key>
	<string>automatic</string>
	<key>manageAppVersionAndBuildNumber</key>
	<false/>
</dict>
</plist>
PLIST

echo "▶ Archiviere (Build $BUILD_NUMBER) …"
xcodebuild archive \
  -project IPTV.xcodeproj \
  -scheme IPTV \
  -configuration Release \
  -destination 'generic/platform=iOS' \
  -archivePath "$ARCHIVE" \
  -allowProvisioningUpdates \
  DEVELOPMENT_TEAM="$TEAM_ID" \
  CURRENT_PROJECT_VERSION="$BUILD_NUMBER" \
  | grep -E "error:|ARCHIVE (SUCCEEDED|FAILED)" || true

[ -d "$ARCHIVE" ] || { echo "✗ Archivieren fehlgeschlagen. Ohne Filter erneut ausführen, um Details zu sehen."; exit 1; }

echo "▶ Lade zu App Store Connect hoch …"
xcodebuild -exportArchive \
  -archivePath "$ARCHIVE" \
  -exportOptionsPlist "$BUILD_DIR/ExportOptions.plist" \
  -exportPath "$BUILD_DIR/export" \
  -allowProvisioningUpdates \
  | grep -E "error:|Upload|EXPORT (SUCCEEDED|FAILED)" || true

echo "✓ Fertig. Nach 5–30 Minuten Verarbeitung erscheint der Build in App Store Connect"
echo "  unter TestFlight und dann in der TestFlight-App auf dem iPhone."
