"""P01 用例 ``test_config_required_missing``（Python 侧）。

以**真实子进程**启动服务入口 ``python -m app``，判定：

* 缺必填环境变量 → 进程退出码非 0，且 stderr 里能读到**变量名**；
* 配齐必填项 → 退出码 0。

不用「直接实例化 Settings」代替，因为验收标准写的是「启动即失败」，
必须证明进程级行为，而不是只证明某个类会抛异常。
"""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

import pytest

PYTHON_RAG_ROOT = Path(__file__).resolve().parents[1]

# 必填变量 → 占位值。占位值不需要真实可用（P01 不连接任何外部服务）。
REQUIRED_ENV: dict[str, str] = {
    "GRPC_TLS_CERT_PATH": "/tmp/rag-server.pem",
    "GRPC_TLS_KEY_PATH": "/tmp/rag-server.key",
    "HMAC_KEY_ID": "k1",
    "HMAC_SECRET": "dummy-hmac-secret",
    "QDRANT_URL": "http://127.0.0.1:6333",
    "EMBEDDING_BASE_URL": "https://embedding.example.com/v1",
    "EMBEDDING_API_KEY": "dummy-embedding-key",
    "EMBEDDING_MODEL": "dummy-embedding-model",
    "LLM_BASE_URL": "https://api.deepseek.com/v1",
    "LLM_API_KEY": "dummy-llm-key",
    "LLM_MODEL": "deepseek-chat",
}


def _clean_env() -> dict[str, str]:
    """基于当前环境，先剔除所有必填项再填占位值，避免开发机上的同名变量干扰结果。"""
    env = {k: v for k, v in os.environ.items() if k not in REQUIRED_ENV}
    env.update(REQUIRED_ENV)
    return env


def _run(env: dict[str, str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, "-m", "app"],
        cwd=PYTHON_RAG_ROOT,
        env=env,
        capture_output=True,
        text=True,
        timeout=60,
    )


@pytest.mark.parametrize("variable", sorted(REQUIRED_ENV))
def test_config_required_missing(variable: str) -> None:
    """逐个必填变量验证：缺任意一项都必须启动失败并报出该变量名。

    参数来自 REQUIRED_ENV，新增必填项时只要登记进该字典即自动纳入覆盖，
    避免出现「Java 侧逐个验证、Python 侧只抽查三个」的不对称。
    """
    env = {k: v for k, v in _clean_env().items() if k != variable}

    result = _run(env)

    assert result.returncode != 0, f"缺 {variable} 时进程必须启动失败"
    assert variable in (result.stderr + result.stdout), (
        f"失败信息必须报出变量名 {variable}，实际 stderr={result.stderr!r}"
    )


def test_config_complete_exits_zero() -> None:
    result = _run(_clean_env())

    assert result.returncode == 0, f"配置齐全时应正常退出，实际 stderr={result.stderr!r}"
    assert "配置校验通过" in result.stdout


def test_config_error_message_lists_all_missing_vars() -> None:
    env = {k: v for k, v in _clean_env().items()}
    env.pop("HMAC_SECRET")
    env.pop("LLM_MODEL")

    result = _run(env)

    assert result.returncode != 0
    assert "HMAC_SECRET" in result.stderr
    assert "LLM_MODEL" in result.stderr
