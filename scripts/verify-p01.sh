#!/usr/bin/env bash
# =============================================================================
# P01 自测脚本：逐条核对 docs/04-开发排期表.md 中 P01 的四项成功判据，并跑两侧用例。
#
# 判据对照：
#   ① mvn generate-sources 成功，产出 RagServiceGrpc.java
#   ② make proto 成功，产出 rag_service_pb2.py / _grpc.py
#   ③ 两侧 protoc 主版本一致
#   ④ 缺必填环境变量时两个服务均启动失败并报出变量名
# 用例：test_config_required_missing（Java/Python）、test_proto_version_match
#
# 用法：bash scripts/verify-p01.sh
# 可选：PY_BIN=/path/to/python3.11 bash scripts/verify-p01.sh
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_DIR="$ROOT/java-service"
PY_DIR="$ROOT/python-rag"

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
echo "使用 Python: $PY_BIN"

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

# ---- 0. Python 依赖 ---------------------------------------------------------
step "0/6 准备 Python 虚拟环境与依赖"
(cd "$PY_DIR" && make --no-print-directory deps PY="$PY_BIN")
PY_RUN="$PY_DIR/.venv/bin/python"
[[ -x "$PY_RUN" ]] || fail "虚拟环境创建失败：$PY_RUN 不存在"
ok "依赖安装完成"

# ---- 1. Java 侧生成 + 编译 --------------------------------------------------
step "1/6 Java：mvn 生成 proto 代码并打包（含编译）"
(cd "$JAVA_DIR" && mvn -B -ntp -q -DskipTests package)
GRPC_JAVA="$JAVA_DIR/target/generated-sources/protobuf/grpc-java/com/ncepu/rag/grpc/RagServiceGrpc.java"
[[ -f "$GRPC_JAVA" ]] || fail "未生成 RagServiceGrpc.java"
ok "RagServiceGrpc.java 已生成"

# ---- 2. Python 侧生成 ------------------------------------------------------
step "2/6 Python：make proto（同时校验两侧 protoc 版本）"
(cd "$PY_DIR" && make --no-print-directory proto PY="$PY_BIN")
[[ -f "$PY_DIR/app/grpc_gen/rag_service_pb2.py" ]] || fail "未生成 rag_service_pb2.py"
[[ -f "$PY_DIR/app/grpc_gen/rag_service_pb2_grpc.py" ]] || fail "未生成 rag_service_pb2_grpc.py"
ok "Python 侧生成物就绪"

# ---- 3. Python 用例 --------------------------------------------------------
step "3/6 Python：pytest（含 test_proto_version_match / test_config_required_missing）"
(cd "$PY_DIR" && "$PY_RUN" -m pytest -q)
ok "pytest 全绿"

# ---- 4. Java 用例 ----------------------------------------------------------
step "4/6 Java：mvn test（AppPropertiesStartupTest 两条失败路径 + 启动成功路径）"
(cd "$JAVA_DIR" && mvn -B -ntp -q test)
ok "mvn test 全绿"

# ---- 断言函数：缺必填变量时必须「非 0 退出」且「输出里能读到变量名」-------
# 写成函数而不再内联，原因见下方「根因」：
#   · 变量一律用 ${var} 显式加花括号。变量名后面紧跟全角标点（例如
#     「退出码=…，日志包含…」里的全角逗号）时，bash 在 UTF-8 locale 下会把
#     该标点的首字节并入变量名，开启 set -u 后直接报 `unbound variable`，
#     脚本在此处以 1 退出——表现为「第 5/6 步失败」，而真正失败的是脚本自己，
#     被测服务的退出码反而没被判断。
#   · 失败时打印进程输出末尾，避免只看到一句断言消息、看不到真实原因。
assert_missing_var_fails() {
  local var="$1"; shift
  local out code

  set +e
  out="$("$@" 2>&1)"
  code=$?
  set -e

  if [[ "$code" -eq 0 ]]; then
    printf '%s\n' "$out" | tail -20
    fail "缺 ${var} 竟然启动成功（exit=0），上面是进程输出末尾 20 行"
  fi

  if ! grep -q -- "$var" <<<"$out"; then
    printf '%s\n' "$out" | tail -20
    fail "退出码=${code} 符合预期，但失败日志里没有 ${var}，上面是进程输出末尾 20 行"
  fi

  ok "退出码=${code}，失败日志包含 ${var}"
}

# ---- 5. Java 缺变量启动失败 -------------------------------------------------
step "5/6 Java：缺 HMAC_SECRET 时启动必须失败并报出变量名"
JAR="$(ls "$JAVA_DIR"/target/java-service-*.jar 2>/dev/null | grep -v original | head -1 || true)"
[[ -n "$JAR" && -f "$JAR" ]] || fail "未找到可执行 jar，请先执行第 1 步 mvn package"
echo "被测 jar: $JAR"

assert_missing_var_fails HMAC_SECRET \
  env -i HOME="$HOME" PATH="$PATH" \
  HMAC_KEY_ID=k1 \
  JWT_SECRET=0123456789abcdef0123456789abcdef \
  RAG_HOST=127.0.0.1 \
  RAG_TLS_CA_PATH=/tmp/rag-ca.pem \
  CORS_ALLOWED_ORIGINS=http://localhost:5173 \
  java -jar "$JAR" --spring.main.web-application-type=none

# ---- 6. Python 缺变量启动失败 -----------------------------------------------
step "6/6 Python：缺 HMAC_SECRET 时启动必须失败并报出变量名"
assert_missing_var_fails HMAC_SECRET \
  env -i HOME="$HOME" PATH="$PATH" PYTHONPATH="$PY_DIR" \
  GRPC_TLS_CERT_PATH=/tmp/x.pem \
  GRPC_TLS_KEY_PATH=/tmp/x.key \
  HMAC_KEY_ID=k1 \
  QDRANT_URL=http://127.0.0.1:6333 \
  EMBEDDING_BASE_URL=https://embedding.example.com/v1 \
  EMBEDDING_API_KEY=k \
  EMBEDDING_MODEL=m \
  LLM_BASE_URL=https://api.deepseek.com/v1 \
  LLM_API_KEY=k \
  LLM_MODEL=deepseek-chat \
  "$PY_RUN" -m app

echo
echo "🎉 P01 自测全部通过（四项判据 + 两组用例）"
