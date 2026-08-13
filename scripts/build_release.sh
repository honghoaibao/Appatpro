#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════
#  build_release.sh — AT PRO Android
# ═══════════════════════════════════════════════════════════════
set -euo pipefail

VERSION="${1:-1.4.7}"
BUILD="${2:-1}"
OUTPUT_DIR="dist"
APK_NAME="att_v${VERSION}_fix${BUILD}.apk"

echo ""
echo "═══════════════════════════════════════"
echo "  AT PRO — Build v$VERSION ($BUILD)"
echo "═══════════════════════════════════════"
echo ""

# Step 1: Ensure local.properties exists
if [ ! -f "android/local.properties" ]; then
  echo "📝 Creating android/local.properties..."
  
  # Detect Flutter SDK path
  FLUTTER_PATH=$(which flutter 2>/dev/null || echo "")
  if [ -z "$FLUTTER_PATH" ]; then
    echo "❌ flutter not found in PATH. Install Flutter first."
    exit 1
  fi
  FLUTTER_SDK=$(dirname $(dirname "$FLUTTER_PATH"))

  # Detect Android SDK
  ANDROID_SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [ -z "$ANDROID_SDK" ]; then
    # Try common locations
    if   [ -d "$HOME/Library/Android/sdk" ]; then ANDROID_SDK="$HOME/Library/Android/sdk"
    elif [ -d "$HOME/Android/Sdk" ];         then ANDROID_SDK="$HOME/Android/Sdk"
    elif [ -d "/usr/local/lib/android/sdk" ]; then ANDROID_SDK="/usr/local/lib/android/sdk"
    else
      echo "❌ Android SDK not found. Set ANDROID_HOME env var."
      exit 1
    fi
  fi

  cat > android/local.properties << PROPS
sdk.dir=$ANDROID_SDK
flutter.sdk=$FLUTTER_SDK
PROPS
  echo "✅ local.properties created"
  echo "   flutter.sdk=$FLUTTER_SDK"
  echo "   sdk.dir=$ANDROID_SDK"
fi

# Step 2: Check signing (optional)
if [ ! -f "android/key.properties" ]; then
  echo "⚠️  No keystore — using debug signing (can install, cannot upload to Play Store)"
  echo "   Run: ./scripts/generate_keystore.sh  to set up release signing"
else
  echo "✅ Release signing: android/key.properties found"
fi

# Step 3: pub get
echo ""
echo "📦 flutter pub get..."
flutter pub get

# Step 4: update version
sed -i.bak "s/^version: .*/version: ${VERSION}+${BUILD}/" pubspec.yaml && rm -f pubspec.yaml.bak

# Step 5: build
echo ""
echo "🔨 Building APK..."
flutter build apk \
  --release \
  --dart-define=APP_VERSION="$VERSION" \
  --dart-define=BUILD_NUMBER="$BUILD"

# Step 6: copy output
mkdir -p "$OUTPUT_DIR"
cp build/app/outputs/flutter-apk/app-release.apk "$OUTPUT_DIR/$APK_NAME"

echo ""
echo "════════════════════════════════════════"
echo "✅ Done!"
echo "   File: $OUTPUT_DIR/$APK_NAME"
echo "   Size: $(du -sh "$OUTPUT_DIR/$APK_NAME" | cut -f1)"
echo ""
echo "Install: adb install $OUTPUT_DIR/$APK_NAME"
echo "════════════════════════════════════════"
