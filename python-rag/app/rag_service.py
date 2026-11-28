"""rag-service 的 RPC 实现（P02：只做「最小打通」）。

本阶段刻意只实现两个 RPC：

* ``HealthCheck``：真实返回服务自身状态（**不**探测 Qdrant/LLM，那是 P04/P06 之后加依赖探测）。
* ``Chat``：**骨架版**——返回固定文本 + ``FINISHED``，不接检索、不调 LLM。
  存在的意义是让 Java 侧的流式接收与帧解析在 P09 之前就能被验证。

其余两个 RPC 显式返回 ``UNIMPLEMENTED``，而不是返回一个"看起来成功"的假结果——
假成功会让 P05/P10 的联调误判为已实现。
"""

from __future__ import annotations

import time
import uuid
from typing import AsyncIterator

import grpc

from app.config import Settings
from app.grpc_gen_loader import pb2, pb2_grpc
from app.interceptors import current_trace_id

SERVICE_NAME = "rag-service"
# 版本号用于 HealthResponse.version，便于联调时确认对端跑的是哪一版。
SERVICE_VERSION = "0.2.0-p02"

# Chat 骨架返回的固定文本。分两段是为了满足「至少 2 个 CONTENT 帧」的验收判据，
# 同时让 Java 侧能验证 seq 的连续性（1, 2, 3=FINISHED）。
_SKELETON_TEXTS = (
    "【P02 骨架】尚未接入 RAG 检索与生成。",
    "已收到你的问题，通信链路正常。",
)


def _now_ms() -> int:
    return int(time.time() * 1000)


def _resolve_trace_id(from_request: str) -> str:
    """trace_id 的取值优先级：请求显式带 > 拦截器注入的 metadata > 服务端生成。

    最后一层兜底不能省：全链路追踪要求 trace_id **永不为空**，
    否则日志里出现空 trace 时无法把一次调用串起来。
    """
    return from_request or current_trace_id() or uuid.uuid4().hex


class RagService(pb2_grpc.RagServiceServicer):
    """gRPC 服务实现。只依赖配置，不持有任何外部连接（P02 无外部依赖）。"""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._started_ms = _now_ms()

    # ---------------------------------------------------------------- Health
    async def HealthCheck(self, request, context) -> pb2.HealthResponse:
        trace_id = _resolve_trace_id(request.trace_id)
        # P02 还没有 Qdrant / LLM 依赖，dependencies 保持为空数组。
        # 注意不要为了"看起来完整"而伪造 qdrant healthy=true —— 那会让 P04 的联调失真。
        return pb2.HealthResponse(
            healthy=True,
            service=SERVICE_NAME,
            version=SERVICE_VERSION,
            dependencies=[],
            trace_id=trace_id,
            timestamp_ms=_now_ms(),
        )

    # ------------------------------------------------------------------ Chat
    async def Chat(self, request, context) -> AsyncIterator[pb2.ChatChunk]:
        trace_id = _resolve_trace_id(request.trace_id)
        seq = 1

        for text in _SKELETON_TEXTS:
            yield pb2.ChatChunk(
                content=text,
                event_type=pb2.CHAT_EVENT_CONTENT,
                trace_id=trace_id,
                seq=seq,
                emitted_at_ms=_now_ms(),
            )
            seq += 1

        # 末帧：finished=True 且 event_type=FINISHED。两者都要设——
        # 只设一个的话，Java 侧按 event_type 分发、按 finished 判断结束，会有一边判断不到。
        yield pb2.ChatChunk(
            finished=True,
            event_type=pb2.CHAT_EVENT_FINISHED,
            trace_id=trace_id,
            seq=seq,
            emitted_at_ms=_now_ms(),
        )

    # -------------------------------------------------------- 未实现的 RPC
    async def IngestDocument(self, request_iterator, context) -> pb2.IngestResult:
        await context.abort(
            grpc.StatusCode.UNIMPLEMENTED,
            "IngestDocument 将在 P05 实现（当前为 P02 骨架）",
        )

    async def DeleteDocument(self, request, context) -> pb2.OperationResult:
        await context.abort(
            grpc.StatusCode.UNIMPLEMENTED,
            "DeleteDocument 将在 P10 实现（当前为 P02 骨架）",
        )
