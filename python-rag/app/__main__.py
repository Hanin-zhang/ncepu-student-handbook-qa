"""rag-service 入口。

两种运行模式：

* ``python -m app``                —— 启动 gRPC 服务（TLS + trace/日志拦截器）
* ``python -m app --check-config`` —— 只做配置校验后退出（供启动校验用例与运维自检）

为什么单独提供 ``--check-config``：
配置用例要断言的是「配置对不对」，而不是「服务能不能跑」。若测试直接启动服务，
进程会一直阻塞在 ``wait_for_termination``，只能靠超时判失败，既慢又说不清原因。
"""

from __future__ import annotations

import argparse
import asyncio
import logging
import signal
import sys

import grpc

from app.config import ConfigError, Settings, load_settings
from app.grpc_gen_loader import pb2_grpc
from app.interceptors import TraceLogInterceptor
from app.rag_service import SERVICE_NAME, SERVICE_VERSION, RagService

logger = logging.getLogger("rag_service")


def _configure_logging(level: str) -> None:
    logging.basicConfig(
        level=getattr(logging, level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
        stream=sys.stderr,
    )


def _build_server(settings: Settings) -> grpc.aio.Server:
    """按 03 §5.5 构建 TLS 服务端：**只开安全端口，不提供明文端口**。"""
    with open(settings.grpc_tls_cert_path, "rb") as fh:
        server_cert = fh.read()
    with open(settings.grpc_tls_key_path, "rb") as fh:
        server_key = fh.read()

    credentials = grpc.ssl_server_credentials([(server_key, server_cert)])

    server = grpc.aio.server(interceptors=[TraceLogInterceptor()])
    pb2_grpc.add_RagServiceServicer_to_server(RagService(settings), server)
    # 注意：绝不 add_insecure_port —— NFR-03 要求明文 gRPC 必须被拒绝。
    server.add_secure_port(f"0.0.0.0:{settings.grpc_port}", credentials)
    return server


async def _serve(settings: Settings) -> int:
    server = _build_server(settings)
    await server.start()

    logger.info(
        "listening on 0.0.0.0:%d service=%s version=%s tls=on %s",
        settings.grpc_port, SERVICE_NAME, SERVICE_VERSION, settings.masked_summary(),
    )
    print(f"[rag-service] listening on 0.0.0.0:{settings.grpc_port}", flush=True)

    stop_event = asyncio.Event()

    def _request_stop(*_args) -> None:
        logger.info("收到停止信号，准备优雅退出")
        stop_event.set()

    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, _request_stop)
        except NotImplementedError:  # pragma: no cover - Windows
            pass

    await stop_event.wait()
    # grace=3：给正在处理的流一点时间收尾，避免联调时被硬切断。
    await server.stop(grace=3)
    logger.info("已停止")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="app", description="华电学生手册 RAG · Python 服务")
    parser.add_argument(
        "--check-config",
        action="store_true",
        help="只校验配置并退出（0=通过，1=缺项或文件不可用）",
    )
    args = parser.parse_args(argv)

    # 先加载配置，失败时立刻退出 1 并打印变量名——这是 NFR-03/04/13 的硬要求。
    try:
        settings = load_settings()
    except ConfigError as exc:
        print(f"[rag-service] 启动失败：{exc}", file=sys.stderr, flush=True)
        return 1

    _configure_logging(settings.log_level)

    if args.check_config:
        print(f"[rag-service] 配置校验通过: {settings.masked_summary()}")
        return 0

    try:
        return asyncio.run(_serve(settings))
    except KeyboardInterrupt:  # pragma: no cover
        return 0


if __name__ == "__main__":
    raise SystemExit(main())
