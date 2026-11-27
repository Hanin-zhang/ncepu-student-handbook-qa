#!/usr/bin/env python3
"""gRPC 冒烟脚本（P02）：不依赖 Java，单独验证 Python 服务的 TLS 与两条链路。

用途：
* 联调时快速确认"服务活着、证书对、帧格式对"，不用先起 Java；
* CI / 本地排查"到底是 Python 的问题还是 Java 的问题"。

用法：
    python scripts/smoke_grpc.py \
        --host 127.0.0.1 --port 50551 --ca deploy/certs/ca.crt

退出码：0=全部通过；1=有断言失败；2=连不上/证书错误。
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import grpc

# 复用 Python 服务的导入引导（它会把 app/grpc_gen 加进 sys.path）
REPO_ROOT = Path(__file__).resolve().parents[1]
PY_ROOT = REPO_ROOT / "python-rag"
sys.path.insert(0, str(PY_ROOT))

from app.grpc_gen_loader import pb2, pb2_grpc  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description="rag-service 冒烟测试")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=50551)
    parser.add_argument("--ca", default=str(REPO_ROOT / "deploy/certs/ca.crt"))
    parser.add_argument("--trace-id", default="smoke-0001")
    args = parser.parse_args()

    ca = Path(args.ca)
    if not ca.is_file():
        print(f"❌ CA 证书不存在：{ca}（先跑 bash scripts/gen-dev-certs.sh）", file=sys.stderr)
        return 2

    credentials = grpc.ssl_channel_credentials(root_certificates=ca.read_bytes())
    channel = grpc.secure_channel(f"{args.host}:{args.port}", credentials)

    try:
        stub = pb2_grpc.RagServiceStub(channel)

        # ---- 1. HealthCheck ----
        health = stub.HealthCheck(
            pb2.HealthRequest(trace_id=args.trace_id), timeout=5.0
        )
        print(f"✅ HealthCheck healthy={health.healthy} service={health.service} version={health.version}")
        if not health.healthy:
            print("❌ healthy=false", file=sys.stderr)
            return 1

        # ---- 2. Chat 流 ----
        frames = list(
            stub.Chat(
                pb2.ChatRequest(
                    user_id="smoke-user",
                    session_id="smoke-session",
                    question="转专业需要什么条件？",
                    trace_id=args.trace_id,
                ),
                timeout=30.0,
            )
        )
        for f in frames:
            print(f"   seq={f.seq} type={pb2.ChatEventType.Name(f.event_type)} content={f.content!r}")

        contents = [f for f in frames if f.event_type == pb2.CHAT_EVENT_CONTENT]
        last = frames[-1] if frames else None
        ok = (
            len(contents) >= 2
            and last is not None
            and last.event_type == pb2.CHAT_EVENT_FINISHED
            and last.finished
            and [f.seq for f in frames] == list(range(1, len(frames) + 1))
        )
        if not ok:
            print("❌ 帧序列不符合预期（需要 ≥2 个 CONTENT 且以 FINISHED 结尾、seq 连续）", file=sys.stderr)
            return 1

        print(f"✅ Chat 共 {len(frames)} 帧，末帧 FINISHED，seq 连续")
        return 0

    except grpc.RpcError as exc:  # 连不上 / 证书不对 / 服务未实现
        code = exc.code() if hasattr(exc, "code") else "UNKNOWN"
        print(f"❌ gRPC 调用失败：{code} {exc.details() if hasattr(exc, 'details') else exc}", file=sys.stderr)
        return 2
    finally:
        channel.close()


if __name__ == "__main__":
    raise SystemExit(main())
