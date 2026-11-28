"""gRPC 服务端拦截器骨架（P02）：trace 透传 + 结构化日志。

设计取舍：

* **只做两件事**：从 metadata 取 ``x-trace-id``（没有就生成）、记录一次调用的
  方法名/耗时/状态。HMAC 校验是 P13 的事，这里**不**假装做了鉴权。
* **不改请求/响应内容**：拦截器只观察，不篡改，避免给后续阶段埋雷。
* **trace_id 用 contextvar 传递**：不通过函数参数层层透传，业务代码用
  :func:`current_trace_id` 随取随用。
* **不打印 Secret**：日志只出方法名、状态码、耗时、trace_id。
"""

from __future__ import annotations

import contextvars
import logging
import time
import uuid
from typing import Any

import grpc

logger = logging.getLogger("rag_service.grpc")

TRACE_ID_KEY = "x-trace-id"

_current_trace_id: contextvars.ContextVar[str] = contextvars.ContextVar(
    "current_trace_id", default=""
)


def current_trace_id() -> str:
    """当前调用链的 trace_id（拦截器未生效时返回空串）。"""
    return _current_trace_id.get()


def _metadata_to_dict(metadata: Any) -> dict[str, str]:
    """metadata 是 (key, value) 二元组序列，且 key 为小写。"""
    result: dict[str, str] = {}
    for item in metadata or ():
        try:
            key, value = item
        except (TypeError, ValueError):
            continue
        result[str(key).lower()] = str(value)
    return result


class TraceLogInterceptor(grpc.aio.ServerInterceptor):
    """入口拦截器：注入 trace_id，并记录每次调用的耗时与终态。"""

    async def intercept_service(self, continuation, handler_call_details):
        metadata = _metadata_to_dict(handler_call_details.invocation_metadata)
        trace_id = metadata.get(TRACE_ID_KEY) or uuid.uuid4().hex
        method = handler_call_details.method or "<unknown>"

        token = _current_trace_id.set(trace_id)
        started = time.perf_counter()

        handler = await continuation(handler_call_details)
        if handler is None:  # 方法不存在，交给 gRPC 自己返回 UNIMPLEMENTED
            _current_trace_id.reset(token)
            return None

        # 只包装本阶段真正会用到的两类方法；其余原样放行，避免过度设计。
        if handler.unary_unary is not None:
            return self._wrap_unary_unary(handler, method, trace_id, token, started)
        if handler.unary_stream is not None:
            return self._wrap_unary_stream(handler, method, trace_id, token, started)
        return handler

    def _wrap_unary_unary(self, handler, method, trace_id, token, started):
        async def behavior(request, context):
            try:
                response = await handler.unary_unary(request, context)
                self._log_ok(method, trace_id, started)
                return response
            except Exception as exc:  # noqa: BLE001 - 记录后原样抛出
                self._log_error(method, trace_id, started, exc)
                raise
            finally:
                _current_trace_id.reset(token)

        return grpc.unary_unary_rpc_method_handler(
            behavior,
            request_deserializer=handler.request_deserializer,
            response_serializer=handler.response_serializer,
        )

    def _wrap_unary_stream(self, handler, method, trace_id, token, started):
        async def behavior(request, context):
            frames = 0
            try:
                async for item in handler.unary_stream(request, context):
                    frames += 1
                    yield item
                logger.info(
                    "grpc_call method=%s trace_id=%s status=OK frames=%d elapsed_ms=%.1f",
                    method, trace_id, frames, (time.perf_counter() - started) * 1000,
                )
            except Exception as exc:  # noqa: BLE001
                self._log_error(method, trace_id, started, exc, frames=frames)
                raise
            finally:
                _current_trace_id.reset(token)

        return grpc.unary_stream_rpc_method_handler(
            behavior,
            request_deserializer=handler.request_deserializer,
            response_serializer=handler.response_serializer,
        )

    @staticmethod
    def _log_ok(method: str, trace_id: str, started: float) -> None:
        logger.info(
            "grpc_call method=%s trace_id=%s status=OK elapsed_ms=%.1f",
            method, trace_id, (time.perf_counter() - started) * 1000,
        )

    @staticmethod
    def _log_error(method: str, trace_id: str, started: float, exc: Exception, frames: int = 0) -> None:
        code = "UNKNOWN"
        if isinstance(exc, grpc.aio.AbortError):
            code = "ABORTED"
        logger.warning(
            "grpc_call method=%s trace_id=%s status=%s frames=%d elapsed_ms=%.1f error=%s",
            method, trace_id, code, frames,
            (time.perf_counter() - started) * 1000, type(exc).__name__,
        )
