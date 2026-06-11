#!/bin/bash
# generate-dev-cert.sh — 生成开发环境自签 SSL 证书
# OpenSSL 3.x 兼容语法（-addext）
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
mkdir -p "$SCRIPT_DIR"

openssl req -x509 \
    -newkey rsa:2048 \
    -keyout "$SCRIPT_DIR/privkey.pem" \
    -out "$SCRIPT_DIR/fullchain.pem" \
    -days 3650 \
    -nodes \
    -subj "/CN=localhost" \
    -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"

chmod 600 "$SCRIPT_DIR/privkey.pem"
chmod 644 "$SCRIPT_DIR/fullchain.pem"

echo "✓ Development SSL certificates generated:"
echo "  Certificate: $SCRIPT_DIR/fullchain.pem"
echo "  Private key: $SCRIPT_DIR/privkey.pem"
