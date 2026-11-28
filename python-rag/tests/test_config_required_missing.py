"""P01/P02 用例 ``test_config_required_missing``（Python 侧）。

以**真实子进程**运行 ``python -m app --check-config``，判定：

* 缺必填环境变量 → 进程退出码非 0，且 stderr 里能读到**变量名**；
* 证书文件不存在 → 同样启动失败，且报出**变量名**（P02 新增：路径非空 ≠ 文件可用）；
* 配齐必填项 → 退出码 0。

为什么用 ``--check-config``：验收标准是「启动即失败」，但不能让进程真的去监听端口——
否则每条用例都要等超时，且失败原因会被「端口占用」之类的噪声掩盖。
"""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

import pytest

PYTHON_RAG_ROOT = Path(__file__).resolve().parents[1]

REQUIRED_VARIABLES = [
    "GRPC_TLS_CERT_PATH",
    "GRPC_TLS_KEY_PATH",
    "HMAC_KEY_ID",
    "HMAC_SECRET",
    "QDRANT_URL",
    "EMBEDDING_BASE_URL",
    "EMBEDDING_API_KEY",
    "EMBEDDING_MODEL",
    "LLM_BASE_URL",
    "LLM_API_KEY",
    "LLM_MODEL",
]


@pytest.fixture(scope="module")
def tls_files(tmp_path_factory: pytest.TempPathFactory) -> tuple[str, str]:
    """造两个真实存在的文件当证书/私钥。

    内容无所谓（``--check-config`` 只校验文件存在可读，不做 TLS 握手），
    但**必须是真实文件**：P02 起「路径非空」已经不够了。
    """
    base = tmp_path_factory.mktemp("tls")
    cert = base / "server.crt"
    key = base / "server.key"
    cert.write_text("-----BEGIN CERTIFICATE-----\ndummy\n-----END CERTIFICATE-----\n")
    key.write_text("-----BEGIN PRIVATE KEY-----\ndummy\n-----END PRIVATE KEY-----\n")
    return str(cert), str(key)


def _required_env(tls_files: tuple[str, str]) -> dict[str, str]:
    """必填变量 → 占位值。占位值不需要真实可用（P01/P02 不连接任何外部服务）。"""
    cert, key = tls_files
    return {
        "GRPC_TLS_CERT_PATH": cert,
        "GRPC_TLS_KEY_PATH": key,
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


def _clean_env(tls_files: tuple[str, str]) -> dict[str, str]:
    """基于当前环境，先剔除所有必填项再填占位值，避免开发机上的同名变量干扰结果。"""
    required = _required_env(tls_files)
    env = {k: v for k, v in os.environ.items() if k not in required}
    env.update(required)
    return env


def _run(env: dict[str, str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, "-m", "app", "--check-config"],
        cwd=PYTHON_RAG_ROOT,
        env=env,
        capture_output=True,
        text=True,
        timeout=60,
    )


@pytest.mark.parametrize("variable", REQUIRED_VARIABLES)
def test_config_required_missing(variable: str, tls_files: tuple[str, str]) -> None:
    """逐个必填变量验证：缺任意一项都必须启动失败并报出该变量名。"""
    env = {k: v for k, v in _clean_env(tls_files).items() if k != variable}

    result = _run(env)

    assert result.returncode != 0, f"缺 {variable} 时进程必须启动失败"
    assert variable in (result.stderr + result.stdout), (
        f"失败信息必须报出变量名 {variable}，实际 stderr={result.stderr!r}"
    )


def test_tls_file_missing_fails_fast(tls_files: tuple[str, str]) -> None:
    """P02 新增：变量给了、但文件不存在，也必须启动失败并报出变量名。

    这条守的是 P01 遗留的洞——只校验「路径非空」时，服务会带着一个不存在的证书路径
    启动成功，等到真正建 TLS Server 才炸，而那时的报错通常像「握手失败」。
    """
    env = _clean_env(tls_files)
    env["GRPC_TLS_CERT_PATH"] = "/nonexistent/definitely-not-here.crt"

    result = _run(env)

    assert result.returncode != 0, "证书文件不存在时必须启动失败"
    assert "GRPC_TLS_CERT_PATH" in result.stderr, (
        f"失败信息必须报出变量名，实际 stderr={result.stderr!r}"
    )


def test_config_complete_exits_zero(tls_files: tuple[str, str]) -> None:
    result = _run(_clean_env(tls_files))

    assert result.returncode == 0, f"配置齐全时应正常退出，实际 stderr={result.stderr!r}"
    assert "配置校验通过" in result.stdout


def test_config_error_message_lists_all_missing_vars(tls_files: tuple[str, str]) -> None:
    env = _clean_env(tls_files)
    env.pop("HMAC_SECRET")
    env.pop("LLM_MODEL")

    result = _run(env)

    assert result.returncode != 0
    assert "HMAC_SECRET" in result.stderr
    assert "LLM_MODEL" in result.stderr
