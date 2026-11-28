#!/usr/bin/env bash
# =============================================================================
# 生成本地开发用自签证书（P02 起 TLS 联调需要）。
#
# 产出（deploy/certs/，已在 .gitignore 中排除，绝不入库）：
#   ca.crt / ca.key      自签 CA（Java 侧作为信任根，Python 侧用于签发服务端证书）
#   server.crt / server.key  服务端证书，SAN 含 DNS:localhost 与 IP:127.0.0.1
#
# 为什么 SAN 必须同时含 localhost 与 127.0.0.1：
#   Java 的 gRPC 会按「连接地址」校验服务端证书的 SAN。用 127.0.0.1 连接时，
#   证书里必须有 IP:127.0.0.1，否则握手直接失败，且报错信息不指向真正原因
#   （只写 CN=localhost 是不够的，现代 TLS 已不认 CN 作为主机名）。
#
# 用法：
#   bash scripts/gen-dev-certs.sh           # 已存在则跳过
#   FORCE=1 bash scripts/gen-dev-certs.sh   # 强制重建
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CERT_DIR="$ROOT/deploy/certs"
DAYS="${DAYS:-365}"

CA_CRT="$CERT_DIR/ca.crt"
CA_KEY="$CERT_DIR/ca.key"
SRV_CRT="$CERT_DIR/server.crt"
SRV_KEY="$CERT_DIR/server.key"

ok()   { echo "✅ $1"; }
info() { echo "· $1"; }

if ! command -v openssl >/dev/null 2>&1; then
  echo "❌ 未找到 openssl，无法生成证书" >&2
  exit 1
fi

if [[ -f "$CA_CRT" && -f "$SRV_CRT" && "${FORCE:-0}" != "1" ]]; then
  ok "证书已存在，跳过（如需重建：FORCE=1 bash scripts/gen-dev-certs.sh）"
  exit 0
fi

mkdir -p "$CERT_DIR"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# ---- 1. 自签 CA -------------------------------------------------------------
info "生成 CA（有效期 ${DAYS} 天）"
openssl req -x509 -newkey rsa:2048 -nodes \
  -keyout "$CA_KEY" -out "$CA_CRT" -days "$DAYS" -sha256 \
  -subj "/C=CN/O=NCEPU-Student-RAG/CN=student-rag-dev-ca" \
  >/dev/null 2>&1

# ---- 2. 服务端证书（带 SAN）-------------------------------------------------
cat > "$TMP/san.cnf" <<'CNF'
[req]
distinguished_name = req_distinguished_name
req_extensions     = v3_req
prompt             = no

[req_distinguished_name]
C  = CN
O  = NCEPU-Student-RAG
CN = localhost

[v3_req]
basicConstraints = CA:FALSE
keyUsage         = critical, digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName   = @alt_names

[alt_names]
DNS.1 = localhost
IP.1  = 127.0.0.1
CNF

info "生成服务端证书（SAN: DNS:localhost, IP:127.0.0.1）"
openssl req -newkey rsa:2048 -nodes \
  -keyout "$SRV_KEY" -out "$TMP/server.csr" \
  -subj "/C=CN/O=NCEPU-Student-RAG/CN=localhost" \
  -config "$TMP/san.cnf" >/dev/null 2>&1

openssl x509 -req -in "$TMP/server.csr" \
  -CA "$CA_CRT" -CAkey "$CA_KEY" -CAcreateserial \
  -out "$SRV_CRT" -days "$DAYS" -sha256 \
  -extfile "$TMP/san.cnf" -extensions v3_req \
  >/dev/null 2>&1

chmod 600 "$CA_KEY" "$SRV_KEY" 2>/dev/null || true

# ---- 3. 自检：证书链与 SAN 是否可用 -----------------------------------------
openssl verify -CAfile "$CA_CRT" "$SRV_CRT" >/dev/null 2>&1 \
  || { echo "❌ 证书链校验失败" >&2; exit 1; }

openssl x509 -in "$SRV_CRT" -noout -text | grep -q "IP Address:127.0.0.1" \
  || { echo "❌ 服务端证书 SAN 缺少 IP:127.0.0.1，Java 按 IP 连接会握手失败" >&2; exit 1; }

ok "证书已生成：$CERT_DIR"
echo "   CA     : $CA_CRT   （Java 侧 RAG_TLS_CA_PATH 指向它）"
echo "   服务端 : $SRV_CRT / $SRV_KEY   （Python 侧 GRPC_TLS_CERT_PATH / GRPC_TLS_KEY_PATH）"
echo "   到期日 : $(openssl x509 -in "$SRV_CRT" -noout -enddate | cut -d= -f2)"
