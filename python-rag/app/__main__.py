"""rag-service 入口（P01）。

P01 阶段只做「配置脚手架 + 启动即校验」：校验通过就退出 0，缺必填项就退出 1 并打印变量名。
gRPC Server 的起停、拦截器链在 P02 接入；此处不伪造一个空的监听端口。
"""

from __future__ import annotations

import sys

from app.config import ConfigError, load_settings


def main() -> int:
    try:
        settings = load_settings()
    except ConfigError as exc:
        print(f"[rag-service] 启动失败：{exc}", file=sys.stderr)
        return 1

    print(f"[rag-service] 配置校验通过: {settings.masked_summary()}")
    print("[rag-service] P01 仅配置脚手架；gRPC Server 将在 P02 启动。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
