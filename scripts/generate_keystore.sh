#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════
#  generate_keystore.sh — AT PRO Android
#  Tạo keystore để ký APK release
#
#  Usage:
#    chmod +x scripts/generate_keystore.sh
#    ./scripts/generate_keystore.sh
# ═══════════════════════════════════════════════════════════════

set -euo pipefail

KEYSTORE_DIR="android/keystore"
KEYSTORE_FILE="$KEYSTORE_DIR/atpro-release.jks"
KEY_PROPS="android/key.properties"

echo ""
echo "═══════════════════════════════════════"
echo "  AT PRO — Keystore Generator"
echo "═══════════════════════════════════════"
echo ""

# Kiểm tra keystore đã tồn tại
if [ -f "$KEYSTORE_FILE" ]; then
    echo "⚠️  Keystore đã tồn tại: $KEYSTORE_FILE"
    read -r -p "   Tạo lại? (y/N): " confirm
    if [[ "$confirm" != "y" && "$confirm" != "Y" ]]; then
        echo "Bỏ qua."
        exit 0
    fi
fi

mkdir -p "$KEYSTORE_DIR"

# Input
echo "Nhập thông tin keystore:"
echo ""
read -r -p "  Tên tổ chức/tên của bạn: " CN
read -r -p "  Tên đơn vị (để trống nếu không có): " OU
OU=${OU:-"Development"}
read -r -p "  Thành phố: " L
L=${L:-"Hanoi"}
read -r -p "  Tỉnh/bang: " ST
ST=${ST:-"Hanoi"}
read -r -p "  Quốc gia (2 chữ, vd VN): " C
C=${C:-"VN"}
echo ""
read -r -s -p "  Mật khẩu keystore (min 6 ký tự): " STORE_PASS
echo ""
read -r -s -p "  Xác nhận mật khẩu: " STORE_PASS2
echo ""

if [ "$STORE_PASS" != "$STORE_PASS2" ]; then
    echo "❌ Mật khẩu không khớp!"
    exit 1
fi

read -r -s -p "  Mật khẩu key (Enter = same as keystore): " KEY_PASS
echo ""
KEY_PASS=${KEY_PASS:-$STORE_PASS}

# Generate keystore
echo ""
echo "🔑 Đang tạo keystore..."

keytool -genkeypair \
    -v \
    -keystore "$KEYSTORE_FILE" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -alias atpro \
    -storepass "$STORE_PASS" \
    -keypass "$KEY_PASS" \
    -dname "CN=$CN, OU=$OU, L=$L, ST=$ST, C=$C"

echo ""
echo "✅ Keystore tạo thành công: $KEYSTORE_FILE"

# Tạo key.properties
cat > "$KEY_PROPS" << PROPS
storePassword=$STORE_PASS
keyPassword=$KEY_PASS
keyAlias=atpro
storeFile=../keystore/atpro-release.jks
PROPS

echo "✅ key.properties đã tạo: $KEY_PROPS"
echo ""
echo "⚠️  QUAN TRỌNG:"
echo "   1. Backup file keystore: $KEYSTORE_FILE"
echo "   2. KHÔNG commit key.properties và keystore lên git!"
echo "   3. Đã thêm vào .gitignore chưa? (kiểm tra bên dưới)"
echo ""

# Kiểm tra .gitignore
if grep -q "key.properties" .gitignore 2>/dev/null; then
    echo "✅ .gitignore đã có key.properties"
else
    echo "android/key.properties" >> .gitignore
    echo "android/keystore/" >> .gitignore
    echo "✅ Đã thêm vào .gitignore"
fi

echo ""
echo "Tiếp theo: flutter build apk --release"
echo ""
