"""环境变量配置脚手架（03 §9.2 的 Python 侧全量清单）。

设计要点：

* **必填项不设默认值**（``Field(...)``）。缺了就抛错并报出**环境变量名**，
  不做「静默用默认值」的容错——TLS / HMAC 这类安全配置静默降级就是事故。
* 本模块**只做校验与建模**，不发起任何外部连接；连接能力在 P02 之后逐步接入。
* Secret 字段提供 :meth:`Settings.masked_summary`，日志里只出现 ``***``。
"""

from __future__ import annotations

from pydantic import Field, ValidationError
from pydantic_settings import BaseSettings, SettingsConfigDict

# 字段名 → 环境变量名。用于把 pydantic 的 missing 错误翻译成用户看得懂的变量名。
FIELD_TO_ENV: dict[str, str] = {
    "grpc_tls_cert_path": "GRPC_TLS_CERT_PATH",
    "grpc_tls_key_path": "GRPC_TLS_KEY_PATH",
    "hmac_key_id": "HMAC_KEY_ID",
    "hmac_secret": "HMAC_SECRET",
    "qdrant_url": "QDRANT_URL",
    "embedding_base_url": "EMBEDDING_BASE_URL",
    "embedding_api_key": "EMBEDDING_API_KEY",
    "embedding_model": "EMBEDDING_MODEL",
    "llm_base_url": "LLM_BASE_URL",
    "llm_api_key": "LLM_API_KEY",
    "llm_model": "LLM_MODEL",
}

_SECRET_FIELDS = frozenset(
    {"hmac_secret", "qdrant_api_key", "embedding_api_key", "llm_api_key"}
)


class ConfigError(RuntimeError):
    """必填环境变量缺失。消息里必须包含变量名，供启动日志与运维排查直接定位。"""

    def __init__(self, missing: list[str]) -> None:
        self.missing = missing
        super().__init__("缺少必填环境变量: " + ", ".join(missing))


class Settings(BaseSettings):
    """与 03 §9.2 一一对应的运行时配置。"""

    model_config = SettingsConfigDict(extra="ignore")

    # ---- gRPC 服务端 ----
    grpc_port: int = 50051
    grpc_tls_cert_path: str = Field(...)  # 必填；不配则拒绝启动（禁止明文，NFR-03）
    grpc_tls_key_path: str = Field(...)   # 必填 · Secret

    # ---- 服务间 HMAC ----
    hmac_key_id: str = Field(...)         # 必填，与 Java 一致
    hmac_secret: str = Field(...)         # 必填 · Secret，与 Java 一致
    hmac_clock_skew_seconds: int = 60
    nonce_cache_max: int = 20000

    # ---- Qdrant ----
    qdrant_url: str = Field(...)          # 必填
    qdrant_api_key: str = ""              # Secret，Qdrant 开启鉴权时必填
    qdrant_collection: str = "campus_policy_chunks"

    # ---- 文档存储 ----
    doc_storage_dir: str = "./storage/documents"

    # ---- Embedding（云端默认，UD-05）----
    embedding_provider: str = "cloud"     # local（本地 BGE）为备选分支，一期不实现
    embedding_base_url: str = Field(...)  # 必填 · 待确认 Q-02
    embedding_api_key: str = Field(...)   # 必填 · Secret
    embedding_model: str = Field(...)     # 必填 · 决定向量维度，禁止硬编码维数
    embedding_batch_size: int = 32

    # ---- LLM（DeepSeek，用户已确认厂商；Key 与配额待确认 Q-01）----
    llm_provider: str = "cloud"
    llm_base_url: str = Field(...)        # 必填
    llm_api_key: str = Field(...)         # 必填 · Secret
    llm_model: str = Field(...)           # 必填
    llm_timeout_connect: int = 10
    llm_timeout_first_token: int = 30
    llm_timeout_total: int = 120

    # ---- 检索与分块（均为建议值，待确认）----
    retrieval_top_k: int = 5
    retrieval_min_score: float = 0.0
    chunk_target_tokens: int = 400
    chunk_max_tokens: int = 600
    chunk_overlap_tokens: int = 80
    chunk_min_tokens: int = 60

    # ---- Skill / 日志 ----
    skill_name: str = "campus-policy-qa"
    log_level: str = "INFO"

    def masked_summary(self) -> str:
        """返回一行脱敏摘要，供启动日志使用（不含任何 Secret 值）。"""
        pairs = [
            f"grpc_port={self.grpc_port}",
            f"hmac_key_id={self.hmac_key_id}",
            f"qdrant_url={self.qdrant_url}",
            f"qdrant_collection={self.qdrant_collection}",
            f"embedding_provider={self.embedding_provider}",
            f"embedding_model={self.embedding_model}",
            f"llm_provider={self.llm_provider}",
            f"llm_model={self.llm_model}",
            f"retrieval_top_k={self.retrieval_top_k}",
            f"chunk_max_tokens={self.chunk_max_tokens}",
            f"skill_name={self.skill_name}",
        ]
        return ", ".join(pairs)


def load_settings() -> Settings:
    """读取并校验环境变量；缺必填项时抛 :class:`ConfigError`，消息含变量名。"""
    try:
        return Settings()
    except ValidationError as exc:
        missing: list[str] = []
        for err in exc.errors():
            if err.get("type") != "missing":
                continue
            field = str(err["loc"][0])
            missing.append(FIELD_TO_ENV.get(field, field.upper()))
        if missing:
            raise ConfigError(missing) from exc
        raise
