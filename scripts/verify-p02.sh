#!/usr/bin/env bash
# =============================================================================
# P02 自测脚本：本地 Java ↔ Python gRPC 最小打通（TLS）。
#
# 判据对照（docs/04 §2 P02）：
#   ① 本机起 Python 服务，Java 单测能连上并拿到 HealthResponse.healthy=true
#   ② Chat 能收到 ≥2 个 CONTENT 帧与 1 个 FINISHED 帧
#   ③ 停掉 Python 后 HealthCheck 返回 UNAVAILABLE 且 Java 报 3001
#
# 用例：test_chat_stream_frames、e2e_rag_down、test_grpc_error_mapping
#
# 用法：bash scripts/verify-p02.sh
# 可选：PY_BIN=/path/to/python3.11 bash scripts/verify-p02.sh
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_DIR="$ROOT/java-service"
PY_DIR="$ROOT/python-rag"
CA="$ROOT/deploy/certs/ca.crt"
SRV_CRT="$ROOT/deploy/certs/server.crt"
SRV_KEY="$ROOT/deploy/certs/server.key"
PORT="${PORT:-50551}"
LOG="$ROOT/.p02-server.log"

step() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }
ok()   { echo "✅ $1"; }
fail() { echo "❌ $1"; exit 1; }

# ---- 定位 Python 3.11 -------------------------------------------------------
PY_BIN="${PY_BIN:-}"
if [[ -z "$PY_BIN" ]]; then
  if command -v python3.11 >/dev/null 2>&1; then
    PY_BIN="$(command -v python3.11)"
  elif command -v uv >/dev/null 2>&1; then
    PY_BIN="$(uv python find 3.11 2>/dev/null || true)"
  fi
fi
[[ -n "$PY_BIN" && -x "$PY_BIN" ]] || fail "找不到 Python 3.11（可用 PY_BIN=... 指定）"

# ---- 自检：禁止「变量名紧跟非 ASCII 字符」的写法 ----------------------------
# 本次 exit=1 的根因即此类写法：变量名后紧跟全角逗号时，bash 在 UTF-8 locale 下
# 会把标点首字节并入变量名，set -u 直接报 unbound variable。改用 ${var} 界定。
# 静态扫描本脚本，防止同类写法再次混入。
"$PY_BIN" - "$0" <<'PY_CHECK'
import re
import sys

path = sys.argv[1]
pattern = re.compile(r"\$[A-Za-z_][A-Za-z0-9_]*(?=[^\x00-\x7F])")
bad = []
with open(path, encoding="utf-8") as fh:
    for lineno, line in enumerate(fh, 1):
        for match in pattern.finditer(line):
            bad.append((lineno, match.group(0), line.strip()[:60]))

if bad:
    print("发现「变量名紧跟非 ASCII 字符」的写法（请改用 ${var}）:", file=sys.stderr)
    for lineno, var, snippet in bad:
        print("  第 %d 行 %s -> %s" % (lineno, var, snippet), file=sys.stderr)
    sys.exit(1)
PY_CHECK

# ---- 0. 证书 + 生成物 ------------------------------------------------------
step "0/6 准备：开发证书 + 两侧 proto 生成物"
bash "$ROOT/scripts/gen-dev-certs.sh" >/dev/null
[[ -f "$CA" && -f "$SRV_CRT" && -f "$SRV_KEY" ]] || fail "证书缺失：$CA"
ok "证书就绪（CA / 服务端证书 / 私钥）"

(cd "$JAVA_DIR" && mvn -B -ntp -q generate-sources)
(cd "$PY_DIR" && make --no-print-directory proto PY="$PY_BIN" >/dev/null)
PY_RUN="$PY_DIR/.venv/bin/python"
[[ -x "$PY_RUN" ]] || fail "虚拟环境不存在：${PY_RUN}（先跑 scripts/verify-p01.sh 或 make deps）"
ok "两侧生成物就绪"

# ---- 1. Python 单元测试（帧语义 + 配置校验）--------------------------------
step "1/6 Python：pytest（帧语义 / 配置校验 / proto）"
(cd "$PY_DIR" && TMPDIR="${TMPDIR:-/tmp}" "$PY_RUN" -m pytest -q)
ok "pytest 全绿"

# ---- 2. 起 Python 服务（TLS）-----------------------------------------------
step "2/6 启动 Python rag-service（TLS，端口 ${PORT}）"
cleanup() {
  if [[ -n "${SERVER_PID:-}" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
    kill -TERM "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

(
  cd "$PY_DIR"
  exec env \
    GRPC_PORT="$PORT" \
    GRPC_TLS_CERT_PATH="$SRV_CRT" \
    GRPC_TLS_KEY_PATH="$SRV_KEY" \
    HMAC_KEY_ID=k1 HMAC_SECRET=dev-secret \
    QDRANT_URL=http://127.0.0.1:6333 \
    EMBEDDING_BASE_URL=https://embedding.example.com/v1 \
    EMBEDDING_API_KEY=dev EMBEDDING_MODEL=dev-model \
    LLM_BASE_URL=https://api.deepseek.com/v1 \
    LLM_API_KEY=dev LLM_MODEL=deepseek-chat \
    "$PY_RUN" -m app
) >"$LOG" 2>&1 &
SERVER_PID=$!

# 等端口就绪（最多 20s）。用端口探测而不是 grep 日志，避免日志格式变化导致脚本脆。
for _ in $(seq 1 40); do
  if "$PY_RUN" - "$PORT" <<'PY' >/dev/null 2>&1
import socket, sys
s = socket.socket()
s.settimeout(0.3)
try:
    s.connect(("127.0.0.1", int(sys.argv[1])))
except OSError:
    sys.exit(1)
finally:
    s.close()
PY
  then
    break
  fi
  sleep 0.5
done

kill -0 "$SERVER_PID" 2>/dev/null || { cat "$LOG"; fail "Python 服务启动失败（日志见上）"; }

# 端口打开后还要等 TLS 真正就绪：用冒烟脚本做一次探测，失败则重试几次
ready=0
for _ in $(seq 1 10); do
  if "$PY_RUN" "$ROOT/scripts/smoke_grpc.py" --port "$PORT" --ca "$CA" >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 0.5
done
[[ "$ready" -eq 1 ]] || { cat "$LOG"; fail "Python 服务未就绪（冒烟探测失败）"; }
ok "Python 服务已就绪（PID=${SERVER_PID}）"

# ---- 3. Python 侧冒烟（独立验证，排除 Java 因素）---------------------------
step "3/6 冒烟：scripts/smoke_grpc.py（纯 Python 客户端）"
"$PY_RUN" "$ROOT/scripts/smoke_grpc.py" --port "$PORT" --ca "$CA"
ok "Python 侧冒烟通过"

# ---- 4. Java 集成用例（判据①② + 用例 test_chat_stream_frames）-------------
step "4/6 Java：mvn test（集成用例连真实 TLS 服务）"
# 用 clean 而非增量编译：实测增量编译在某些情况下会留下不完整的 target/classes，
# 表现为 NoClassDefFoundError / 断言拿到空消息，且重跑就好——这种"薛定谔的绿"最耗排查时间。
(cd "$JAVA_DIR" && mvn -B -ntp -q clean test \
  -Dp02.it=true -Dp02.host=127.0.0.1 -Dp02.port="$PORT" -Dp02.ca="$CA")
ok "Java 集成用例通过（healthy=true / 帧序列正确）"

# ---- 5. 停掉 Python，验证降级（判据③ + e2e_rag_down）----------------------
step "5/6 停掉 Python 后：Java 必须得到 UNAVAILABLE 并映射为 3001"
cleanup
trap - EXIT

# 等端口真正释放，否则可能出现"连上了但立刻断"的中间态
for _ in $(seq 1 20); do
  "$PY_RUN" - "$PORT" <<'PY' >/dev/null 2>&1 || break
import socket, sys
s = socket.socket(); s.settimeout(0.3)
try:
    s.connect(("127.0.0.1", int(sys.argv[1])))
    sys.exit(0)   # 还能连上 → 继续等
except OSError:
    sys.exit(1)   # 已断开 → 跳出循环
finally:
    s.close()
PY
  sleep 0.3
done

# 这里同样用 clean：实测在第 4 步之后紧接着跑，偶发 NoClassDefFoundError: AppProperties
# （类文件存在但加载失败），单独重跑又正常——典型的增量产物不可靠。
# 脚本的存在意义是"结论确定"，多花几秒重新编译，好过让人怀疑代码。
(cd "$JAVA_DIR" && mvn -B -ntp -q clean test \
  -Dtest=RagServiceDownIT -Dp02.down=true -Dp02.host=127.0.0.1 -Dp02.port="$PORT" -Dp02.ca="$CA")
ok "e2e_rag_down 通过（UNAVAILABLE → 3001）"

# ---- 6. 汇总 ---------------------------------------------------------------
step "6/6 清理"
rm -f "$LOG"
ok "临时文件已清理"

echo
echo "🎉 P02 自测全部通过（三条判据 + 三组用例）"
