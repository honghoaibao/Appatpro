#!/bin/bash
# generate_keystore.sh — Tạo release keystore nhất quán cho AT Pro
# Chạy 1 lần, sau đó dùng key.properties để tham chiếu.
#
# Yêu cầu: keytool (có trong JDK, ví dụ: Android Studio bundled JDK)
#
# Cách dùng:
#   chmod +x generate_keystore.sh
#   ./generate_keystore.sh

set -e

KEYSTORE="android/my-release-key.jks"
KEY_ALIAS="atpro"
KEY_PROPS="android/key.properties"

if [ -f "$KEYSTORE" ]; then
  echo "⚠️  Keystore đã tồn tại: $KEYSTORE"
  echo "    Nếu muốn tạo mới, xóa file trên trước."
  exit 0
fi

echo "🔑 Đang tạo release keystore..."
keytool -genkeypair \
  -v \
  -keystore "$KEYSTORE" \
  -alias "$KEY_ALIAS" \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storetype JKS

echo ""
echo "✅ Keystore đã tạo: $KEYSTORE"
echo ""

# Ghi key.properties
STORE_PASS=$(read -s -p "Nhập store password vừa tạo: " p && echo "$p")
KEY_PASS=$(read -s -p "Nhập key password vừa tạo: " p && echo "$p")

cat > "$KEY_PROPS" << PROPS
storeFile=my-release-key.jks
storePassword=${STORE_PASS}
keyAlias=${KEY_ALIAS}
keyPassword=${KEY_PASS}
PROPS

echo ""
echo "✅ key.properties đã ghi: $KEY_PROPS"
echo ""
echo "⚠️  QUAN TRỌNG: KHÔNG commit key.properties và *.jks lên git!"
echo "   Chúng đã được liệt kê trong .gitignore."
echo ""
echo "📦 Từ giờ build release bằng:"
echo "   ./gradlew assembleRelease"
