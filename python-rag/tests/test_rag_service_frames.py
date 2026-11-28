"""P02 用例 ``test_chat_stream_frames`` 与 HealthCheck 骨架的单元验证。

为什么直接调用 servicer 而不是起真服务：
本用例要证的是**帧语义**（事件类型、`seq` 连续性、末帧标记），这属于纯逻辑；
真实 TLS 握手与跨进程联通由 ``scripts/verify-p02.sh`` 端到端覆盖。
两者分开，失败时能一眼看出是"帧拼错了"还是"网络/证书不通"。
"""

from __future__ import annotations

import asyncio
from pathlib import Path

import pytest

from app.config import Settings
from app.grpc_gen_loader import pb2
from app.rag_service import SERVICE_NAME, SERVICE_VERSION, RagService


@pytest.fixture()
def settings(tmp_path: Path) -> Settings:
    """构造一份可用配置（证书/私钥用真实临时文件，避免被文件存在性校验拦住）。"""
    cert = tmp_path / "server.crt"
    key = tmp_path / "server.key"
    cert.write_text("-----BEGIN CERTIFICATE-----\ndummy\n-----END CERTIFICATE-----\n")
    key.write_text("-----BEGIN PRIVATE KEY-----\ndummy\n-----END PRIVATE KEY-----\n")
    return Settings(
        grpc_tls_cert_path=str(cert),
        grpc_tls_key_path=str(key),
        hmac_key_id="k1",
        hmac_secret="dummy",
        qdrant_url="http://127.0.0.1:6333",
        embedding_base_url="https://embedding.example.com/v1",
        embedding_api_key="dummy",
        embedding_model="dummy-model",
        llm_base_url="https://api.deepseek.com/v1",
        llm_api_key="dummy",
        llm_model="deepseek-chat",
    )


async def _collect_chat(service: RagService, request) -> list[pb2.ChatChunk]:
    return [chunk async for chunk in service.Chat(request, None)]


def test_chat_stream_frames(settings: Settings) -> None:
    """帧序列：≥2 个 CONTENT，以 FINISHED 结尾，seq 从 1 连续递增，trace_id 全程一致。"""
    service = RagService(settings)
    request = pb2.ChatRequest(
        user_id="u1", session_id="s1", question="转专业需要什么条件？", trace_id="trace-fixed"
    )

    chunks = asyncio.run(_collect_chat(service, request))

    assert len(chunks) >= 3, "至少应有 2 个 CONTENT 帧 + 1 个 FINISHED 帧"

    content_chunks = [c for c in chunks if c.event_type == pb2.CHAT_EVENT_CONTENT]
    assert len(content_chunks) >= 2, "验收要求至少 2 个 CONTENT 帧"

    last = chunks[-1]
    assert last.event_type == pb2.CHAT_EVENT_FINISHED, "末帧事件类型必须是 FINISHED"
    assert last.finished is True, "末帧 finished 必须为 true（Java 侧靠它判断结束）"

    # seq 从 1 开始连续，不允许空洞或重复——前端靠它做乱序容错与丢帧检测
    assert [c.seq for c in chunks] == list(range(1, len(chunks) + 1))

    assert all(c.trace_id == "trace-fixed" for c in chunks), "trace_id 必须全程回显一致"
    assert all(c.content for c in content_chunks), "CONTENT 帧必须带内容"


def test_chat_generates_trace_id_when_absent(settings: Settings) -> None:
    """请求没带 trace_id 时，服务端必须自己生成一个，而不是回显空串。"""
    service = RagService(settings)
    chunks = asyncio.run(_collect_chat(service, pb2.ChatRequest(question="你好")))

    assert chunks[0].trace_id, "trace_id 不应为空"


def test_health_check_reports_service_identity(settings: Settings) -> None:
    service = RagService(settings)

    response = asyncio.run(service.HealthCheck(pb2.HealthRequest(trace_id="t-1"), None))

    assert response.healthy is True
    assert response.service == SERVICE_NAME
    assert response.version == SERVICE_VERSION
    assert response.trace_id == "t-1"
    assert response.timestamp_ms > 0
    # P02 尚无 Qdrant/LLM 依赖：dependencies 必须为空，而不是伪造几条 healthy=true
    assert list(response.dependencies) == []


def test_unimplemented_rpcs_abort_with_unimplemented(settings: Settings) -> None:
    """未实现的 RPC 必须返回 UNIMPLEMENTED，而不是假装成功。"""
    import grpc

    service = RagService(settings)

    class _Ctx:
        async def abort(self, code, details=""):
            raise grpc.aio.AbortError(code, details)

    with pytest.raises(grpc.aio.AbortError):
        asyncio.run(service.DeleteDocument(pb2.DeleteDocumentRequest(document_id="d1"), _Ctx()))
