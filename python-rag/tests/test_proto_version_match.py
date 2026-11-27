"""P01 用例 ``test_proto_version_match``：两侧 protoc 主版本必须一致（NFR-16）。

版本规范化规则（artifact 3.25.1 ↔ protoc 25.1）的唯一实现在
:mod:`app.proto_version`，``make proto`` 与本用例共用，避免口径漂移。
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

from app.proto_version import java_protoc_version, normalize_protoc_version, python_protoc_version

REPO_ROOT = Path(__file__).resolve().parents[2]
PROTO_PATH = REPO_ROOT / "proto" / "rag_service.proto"


def test_proto_version_match() -> None:
    assert python_protoc_version() == java_protoc_version()


def test_normalize_protoc_version() -> None:
    # 记录 artifact → protoc 的映射，防止未来升级 protobuf 时把规则改错
    assert normalize_protoc_version("3.25.1") == "25.1"
    assert normalize_protoc_version("4.28.2") == "28.2"
    assert normalize_protoc_version("25.1") == "25.1"


def test_proto_contract_compiles(tmp_path: Path) -> None:
    """契约本身可编译：任何一侧生成失败都应在这里第一时间暴露。"""
    proc = subprocess.run(
        [
            sys.executable,
            "-m",
            "grpc_tools.protoc",
            f"-I{PROTO_PATH.parent}",
            f"--python_out={tmp_path}",
            f"--grpc_python_out={tmp_path}",
            str(PROTO_PATH),
        ],
        capture_output=True,
        text=True,
    )

    assert proc.returncode == 0, proc.stderr
    assert (tmp_path / "rag_service_pb2.py").exists()
    assert (tmp_path / "rag_service_pb2_grpc.py").exists()
