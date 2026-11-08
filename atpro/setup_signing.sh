#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════
#  setup_signing.sh — AT PRO Android
#  Tạo release keystore + in sẵn 4 GitHub Secrets để paste lên CI
#
#  Chạy 1 lần duy nhất từ thư mục gốc project:
#    chmod +x setup_signing.sh
#    ./setup_signing.sh
#
#  Sau khi chạy xong:
#    1. Copy 4 secrets được in ra → paste lên GitHub (hướng dẫn bên dưới)
#    2. KHÔNG commit file .jks hoặc key.properties (đã có trong .gitignore)
#    3. Backup file android/keystore/atpro-release.jks ra nơi an toàn
# ═══════════════════════════════════════════════════════════════════

set -euo pipefail

KEYSTORE_DIR="android/keystore"
KEYSTORE_FILE="$KEYSTORE_DIR/atpro-release.jks"
KEY_PROPS="android/key.properties"
KEY_ALIAS="atpro"

# ── Màu terminal ──────────────────────────────────────────────────
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
RED='\033[0;31m'
BOLD='\033[1m'
NC='\033[0m'

echo ""
echo -e "${BOLD}═══════════════════════════════════════════${NC}"
echo -e "${BOLD}  AT PRO — Setup Release Signing${NC}"
echo -e "${BOLD}═══════════════════════════════════════════${NC}"
echo ""

# ── Kiểm tra keytool ─────────────────────────────────────────────
if ! command -v keytool &> /dev/null; then
    echo -e "${RED}❌ Không tìm thấy keytool.${NC}"
    echo "   Cài JDK 17 hoặc chạy từ Android Studio terminal."
    exit 1
fi

# ── Kiểm tra keystore đã tồn tại ─────────────────────────────────
if [ -f "$KEYSTORE_FILE" ]; then
    echo -e "${YELLOW}⚠️  Keystore đã tồn tại: $KEYSTORE_FILE${NC}"
    read -r -p "   Tạo lại? Lưu ý: APK cũ sẽ không cài đè được nữa! (y/N): " confirm
    if [[ "$confirm" != "y" && "$confirm" != "Y" ]]; then
        echo "Giữ nguyên keystore cũ."
        # Vẫn in secrets từ keystore hiện có
        SKIP_GENERATE=true
    else
        SKIP_GENERATE=false
    fi
else
    SKIP_GENERATE=false
fi

# ── Nhập mật khẩu ────────────────────────────────────────────────
echo ""
echo -e "${CYAN}Nhập mật khẩu cho keystore:${NC}"
echo -e "  ${YELLOW}(Ghi lại và lưu trữ an toàn — mất mật khẩu = không ký được nữa)${NC}"
echo ""

while true; do
    read -r -s -p "  Mật khẩu keystore (min 6 ký tự): " STORE_PASS
    echo ""
    if [ ${#STORE_PASS} -lt 6 ]; then
        echo -e "  ${RED}Quá ngắn, nhập lại.${NC}"
        continue
    fi
    read -r -s -p "  Xác nhận mật khẩu: " STORE_PASS2
    echo ""
    if [ "$STORE_PASS" != "$STORE_PASS2" ]; then
        echo -e "  ${RED}Không khớp, nhập lại.${NC}"
    else
        break
    fi
done

read -r -s -p "  Mật khẩu key (Enter = giống keystore): " KEY_PASS
echo ""
KEY_PASS=${KEY_PASS:-$STORE_PASS}

# ── Tạo keystore ─────────────────────────────────────────────────
if [ "$SKIP_GENERATE" = false ]; then
    echo ""
    echo -e "${CYAN}🔑 Đang tạo keystore...${NC}"
    mkdir -p "$KEYSTORE_DIR"

    keytool -genkeypair \
        -keystore "$KEYSTORE_FILE" \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -alias "$KEY_ALIAS" \
        -storepass "$STORE_PASS" \
        -keypass "$KEY_PASS" \
        -dname "CN=AT PRO, OU=Dev, L=Hanoi, ST=Hanoi, C=VN" \
        -storetype JKS 2>/dev/null

    echo -e "${GREEN}✅ Keystore đã tạo: $KEYSTORE_FILE${NC}"
fi

# ── Tạo key.properties ───────────────────────────────────────────
cat > "$KEY_PROPS" << PROPS
storePassword=$STORE_PASS
keyPassword=$KEY_PASS
keyAlias=$KEY_ALIAS
storeFile=../keystore/atpro-release.jks
PROPS

echo -e "${GREEN}✅ key.properties đã tạo: $KEY_PROPS${NC}"

# ── Encode base64 ────────────────────────────────────────────────
KEYSTORE_B64=$(base64 < "$KEYSTORE_FILE" | tr -d '\n')

# ── In GitHub Secrets ─────────────────────────────────────────────
echo ""
echo -e "${BOLD}═══════════════════════════════════════════════════════${NC}"
echo -e "${BOLD}  📋 COPY 4 SECRETS NÀY LÊN GITHUB${NC}"
echo -e "${BOLD}═══════════════════════════════════════════════════════${NC}"
echo -e "  ${YELLOW}GitHub repo → Settings → Secrets and variables → Actions${NC}"
echo -e "  ${YELLOW}→ New repository secret${NC}"
echo ""
echo -e "  ${CYAN}Secret 1:${NC}"
echo -e "  Name:  ${BOLD}KEYSTORE_BASE64${NC}"
echo -e "  Value: ${KEYSTORE_B64}"
echo ""
echo -e "  ${CYAN}Secret 2:${NC}"
echo -e "  Name:  ${BOLD}KEYSTORE_PASSWORD${NC}"
echo -e "  Value: ${STORE_PASS}"
echo ""
echo -e "  ${CYAN}Secret 3:${NC}"
echo -e "  Name:  ${BOLD}KEY_ALIAS${NC}"
echo -e "  Value: ${KEY_ALIAS}"
echo ""
echo -e "  ${CYAN}Secret 4:${NC}"
echo -e "  Name:  ${BOLD}KEY_PASSWORD${NC}"
echo -e "  Value: ${KEY_PASS}"
echo ""
echo -e "${BOLD}═══════════════════════════════════════════════════════${NC}"
echo ""
echo -e "${GREEN}✅ Xong! Sau khi paste secrets lên GitHub:${NC}"
echo -e "   1. Trigger build mới → APK sẽ được ký bằng release key"
echo -e "   2. Cài đè APK cũ sẽ không bị conflict nữa"
echo -e "   3. ${YELLOW}Backup $KEYSTORE_FILE ra nơi an toàn (Google Drive, v.v.)${NC}"
echo ""
echo -e "${RED}⚠️  KHÔNG commit .jks hoặc key.properties lên git!${NC}"
echo -e "   (.gitignore đã chặn sẵn)"
echo ""
