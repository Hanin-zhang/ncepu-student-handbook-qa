"""
rag-service 入口模块。

两种运行模式：
- `python -m app`                  —— 正常启动 gRPC 服务（启用 TLS + 全链路追踪日志拦截器）
- `python -m app --check-config`   —— 仅执行配置合法性校验，校验完成立即退出（供测试用例、运维自检使用）

为什么单独提供 `--check-config`：
配置测试的核心目标是验证「配置是否正确」，而非「服务能否正常启动」。
如果测试直接启动服务，进程会永久阻塞在 wait_for_termination 等待终止信号，
只能通过超时来判定失败，既耗时长，也无法精准定位是哪项配置出错。
"""

# 兼容Python低版本的类型注解语法
from __future__ import annotations

import argparse
import asyncio
import logging
import signal
import sys

import grpc

# 项目内部模块导入
from app.config import ConfigError, Settings, load_settings  # 配置加载与自定义异常
from app.grpc_gen_loader import pb2_grpc                        # proto生成的gRPC桩代码加载器
from app.interceptors import TraceLogInterceptor                # 全链路追踪+日志拦截器
from app.rag_service import SERVICE_NAME, SERVICE_VERSION, RagService  # 业务服务实现与元信息

# 模块级日志器，统一使用 rag_service 作为日志标识
logger = logging.getLogger("rag_service")


def _configure_logging(level: str) -> None:
    """
    配置全局日志格式与输出级别。
    统一输出到 stderr，避免和业务标准输出混淆，方便日志采集。
    """
    logging.basicConfig(
        level=getattr(logging, level.upper(), logging.INFO),  # 动态映射日志级别，默认INFO
        format="%(asctime)s %(levelname)s %(name)s %(message)s",  # 标准结构化日志格式
        stream=sys.stderr,  # 日志输出到标准错误流，与业务输出分离
    )


def _build_server(settings: Settings) -> grpc.aio.Server:
    """
    按照架构规范 03 §5.5 构建 TLS 加密的 gRPC 服务端。
    核心原则：只开放安全端口，绝不提供明文监听端口，符合 NFR-03 安全规范。

    Args:
        settings: 全局配置对象，包含端口、证书路径等信息

    Returns:
        构建完成但未启动的 gRPC 异步服务器实例
    """
    # 读取服务端证书与私钥（PEM 格式）
    with open(settings.grpc_tls_cert_path, "rb") as fh:
        server_cert = fh.read()
    with open(settings.grpc_tls_key_path, "rb") as fh:
        server_key = fh.read()

    # 构建 TLS 服务端凭证
    credentials = grpc.ssl_server_credentials([(server_key, server_cert)])

    # 创建异步 gRPC 服务器，注册追踪日志拦截器（所有请求都会经过拦截器打日志）
    server = grpc.aio.server(interceptors=[TraceLogInterceptor()])

    # 将业务服务实现注册到 gRPC 服务器
    pb2_grpc.add_RagServiceServicer_to_server(RagService(settings), server)

    # 注意：绝不调用 add_insecure_port —— NFR-03 强制要求禁止明文 gRPC 通信
    server.add_secure_port(f"0.0.0.0:{settings.grpc_port}", credentials)

    return server


async def _serve(settings: Settings) -> int:
    """
    启动 gRPC 服务并运行，处理系统信号实现优雅退出。

    Args:
        settings: 全局配置对象

    Returns:
        进程退出码，0 表示正常退出
    """
    # 第一步：构建服务器实例
    server = _build_server(settings)
    # 第二步：启动服务（端口开始监听）
    await server.start()

    # 打印启动信息，分别输出到日志和标准输出
    logger.info(
        "listening on 0.0.0.0:%d service=%s version=%s tls=on %s",
        settings.grpc_port, SERVICE_NAME, SERVICE_VERSION, settings.masked_summary(),
    )
    print(f"[rag-service] listening on 0.0.0.0:{settings.grpc_port}", flush=True)

    # 创建停止事件，用于接收信号后通知协程退出
    stop_event = asyncio.Event()

    def _request_stop(*_args) -> None:
        """信号回调函数：收到终止信号时触发，设置停止事件"""
        logger.info("收到停止信号，准备优雅退出")
        stop_event.set()

    # 获取当前运行的事件循环
    loop = asyncio.get_running_loop()
    # 注册系统信号处理：支持 Ctrl+C(SIGINT) 和 系统终止(SIGTERM)
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, _request_stop)
        except NotImplementedError:  # pragma: no cover - Windows 系统不支持信号处理，直接跳过
            pass

    # 阻塞等待停止事件（服务正常运行时一直停在这里）
    await stop_event.wait()

    # 收到停止信号后，优雅关闭服务
    # grace=3：给正在处理中的流式请求预留3秒收尾时间，避免联调时被强制切断连接
    await server.stop(grace=3)
    logger.info("已停止")
    return 0


def main(argv: list[str] | None = None) -> int:
    """
    程序主入口函数，负责参数解析、配置加载、模式分支处理。

    Args:
        argv: 命令行参数列表，不传则默认读取 sys.argv

    Returns:
        进程退出码：0=成功，1=配置校验失败
    """
    # 初始化命令行参数解析器
    parser = argparse.ArgumentParser(prog="app", description="华电学生手册 RAG · Python 服务")
    parser.add_argument(
        "--check-config",
        action="store_true",
        help="只校验配置并退出（0=通过，1=缺项或文件不可用）",
    )
    args = parser.parse_args(argv)

    # ========== 第一步：优先加载配置 ==========
    # 配置加载失败立刻退出并打印具体错误项，符合 NFR-03/04/13 硬性要求
    # 好处：启动前暴露所有配置问题，不用等服务启动一半才报错
    try:
        settings = load_settings()
    except ConfigError as exc:
        print(f"[rag-service] 启动失败：{exc}", file=sys.stderr, flush=True)
        return 1

    # ========== 第二步：配置日志 ==========
    _configure_logging(settings.log_level)

    # ========== 第三步：分支处理运行模式 ==========
    # 模式1：仅配置校验，校验通过直接退出，不启动服务
    if args.check_config:
        print(f"[rag-service] 配置校验通过: {settings.masked_summary()}")
        return 0

    # 模式2：正常启动 gRPC 服务
    try:
        return asyncio.run(_serve(settings))
    except KeyboardInterrupt:  # pragma: no cover  键盘中断直接正常退出
        return 0


if __name__ == "__main__":
    # 程序入口，抛出 SystemExit 并携带退出码
    raise SystemExit(main())
