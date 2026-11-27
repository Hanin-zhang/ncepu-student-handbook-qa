"""两侧 protoc 版本一致性检查（NFR-16 / Q-22）。

为什么要规范化版本号：Maven 中央仓库里 ``com.google.protobuf:protoc`` 的**artifact 版本**
与它内部 ``protoc --version`` 打印的版本**不是同一个字符串**：

* artifact ``3.25.1`` → ``libprotoc 25.1``
* artifact ``4.28.2`` → ``libprotoc 28.2``

规则：artifact 版本首个分量是 ``3`` 或 ``4`` 时，去掉该分量，其余即为 protoc 版本。
本模块是这条规则的**唯一实现**：``make proto`` 直接调用它，pytest 也导入它，
避免 Makefile 与测试各写一份导致口径漂移。
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
POM_PATH = REPO_ROOT / "java-service" / "pom.xml"


def normalize_protoc_version(artifact_version: str) -> str:
    """把 Maven artifact 版本规范化为 protoc 版本（见模块注释）。"""
    parts = artifact_version.strip().split(".")
    if len(parts) >= 2 and parts[0] in {"3", "4"}:
        return ".".join(parts[1:])
    return ".".join(parts)


def java_protoc_version() -> str:
    text = POM_PATH.read_text(encoding="utf-8")
    match = re.search(r"<protoc\.version>\s*([^<\s]+)\s*</protoc\.version>", text)
    if not match:
        raise RuntimeError(f"pom.xml 中找不到 <protoc.version>：{POM_PATH}")
    return normalize_protoc_version(match.group(1))


def python_protoc_version() -> str:
    proc = subprocess.run(
        [sys.executable, "-m", "grpc_tools.protoc", "--version"],
        capture_output=True,
        text=True,
        check=True,
    )
    text = (proc.stdout + proc.stderr).strip()
    match = re.search(r"libprotoc\s+([0-9]+\.[0-9]+(?:\.[0-9]+)?)", text)
    if not match:
        raise RuntimeError(f"无法解析 grpc_tools 的 protoc 版本：{text!r}")
    return match.group(1)


def main() -> int:
    py_ver = python_protoc_version()
    java_ver = java_protoc_version()
    print(f"Python protoc = {py_ver}")
    print(f"Java   protoc = {java_ver}")
    if py_ver != java_ver:
        print("❌ 两侧 protoc 版本不一致", file=sys.stderr)
        return 1
    print("✅ 两侧 protoc 版本一致")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
