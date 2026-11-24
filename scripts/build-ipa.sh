#!/bin/bash
# Baut eine unsignierte IPA der iOS-App zum Installieren mit SideStore oder AltStore.
# SideStore signiert sie beim Installieren mit deiner Apple-ID und erneuert sie
# danach automatisch alle 7 Tage.
#
# Aufruf im Projektordner (auf dem Mac, Xcode muss installiert sein):
#   ./scripts/build-ipa.sh
# Ergebnis: build/IPTV.ipa
set -euo pipefail

cd "$(dirname "$0")/.."

# Vollständiges Xcode verwenden, auch wenn im Terminal nur die „Command Line Tools“
# eingestellt sind (sonst bricht xcodebuild ab).
if ! xcodebuild -version >/dev/null 2>&1; then
  XCODE_APP=$(ls -d /Applications/Xcode*.app 2>/dev/null | sort | tail -1 || true)
  if [ -n "${XCODE_APP:-}" ]; then
    export DEVELOPER_DIR="$XCODE_APP/Contents/Developer"
    echo "▶ Verwende $XCODE_APP"
  else
    echo "✗ Xcode wurde nicht gefunden. Bitte Xcode aus dem App Store installieren."
    exit 1
  fi
fi
BUILD_DIR="$PWD/build"
DERIVED="$BUILD_DIR/DerivedData"

# Versionsnummer aus dem Projekt, Build-Nummer = Anzahl der Commits (steigt mit jeder Änderung).
VERSION=$(grep -m1 "MARKETING_VERSION" IPTV.xcodeproj/project.pbxproj | sed -E 's/.*= ([^;]+);/\1/')
BUILD_NUMBER=$(git rev-list --count HEAD 2>/dev/null || echo 1)

echo "▶ Baue IPTV $VERSION (Build $BUILD_NUMBER), Release, ohne Signatur …"
rm -rf "$BUILD_DIR"
xcodebuild \
  -project IPTV.xcodeproj \
  -scheme IPTV \
  -configuration Release \
  -sdk iphoneos \
  -destination 'generic/platform=iOS' \
  -derivedDataPath "$DERIVED" \
  CODE_SIGNING_ALLOWED=NO \
  CODE_SIGNING_REQUIRED=NO \
  CODE_SIGN_IDENTITY="" \
  CURRENT_PROJECT_VERSION="$BUILD_NUMBER" \
  build | grep -E "error:|warning: .*IPTV|BUILD (SUCCEEDED|FAILED)" || true

APP="$DERIVED/Build/Products/Release-iphoneos/IPTV.app"
if [ ! -d "$APP" ]; then
  echo "✗ Build fehlgeschlagen. Für Details ohne Filter erneut ausführen:"
  echo "  xcodebuild -project IPTV.xcodeproj -scheme IPTV -configuration Release -sdk iphoneos CODE_SIGNING_ALLOWED=NO build"
  exit 1
fi

echo "▶ Packe IPA …"
mkdir -p "$BUILD_DIR/Payload"
cp -R "$APP" "$BUILD_DIR/Payload/"
(cd "$BUILD_DIR" && zip -qry IPTV.ipa Payload)
rm -rf "$BUILD_DIR/Payload"

echo "✓ Fertig: $BUILD_DIR/IPTV.ipa – Version $VERSION (Build $BUILD_NUMBER), $(du -h "$BUILD_DIR/IPTV.ipa" | cut -f1)"
echo "  Per AirDrop aufs iPhone schicken und dort mit SideStore öffnen."
