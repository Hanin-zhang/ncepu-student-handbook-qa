"""把 ``app/grpc_gen`` 加入 ``sys.path`` 后导出生成的桩代码。

protoc 生成的 ``rag_service_pb2_grpc.py`` 用的是**扁平导入**（``import rag_service_pb2``），
而不是包内相对导入——因为 proto 里没有声明 ``python_package`` 选项。直接
``from app.grpc_gen import rag_service_pb2_grpc`` 会报 ModuleNotFoundError。

这里做两件事：

1. 把生成目录插到 ``sys.path`` 最前面，让扁平导入可用；
2. 生成物缺失时给出**可执行的**提示，而不是一句 ImportError。

生成目录本身视为纯产物（``.gitignore`` 已排除），不往里放任何手写文件。
"""

from __future__ import annotations

import sys
from pathlib import Path

GEN_DIR = Path(__file__).resolve().parent / "grpc_gen"

if not GEN_DIR.is_dir():
    raise RuntimeError(f"未找到 proto 生成目录：{GEN_DIR}")

# 必须插到最前面：避免同名的旧模块（例如别的 venv 里的）被优先命中。
_gen = str(GEN_DIR)
if _gen not in sys.path:
    sys.path.insert(0, _gen)

try:  # pragma: no cover - 导入失败只在未生成时发生
    import rag_service_pb2 as pb2  # noqa: E402
    import rag_service_pb2_grpc as pb2_grpc  # noqa: E402
except ImportError as exc:  # pragma: no cover
    raise RuntimeError(
        "未找到 proto 生成物，请先在 python-rag 目录执行 `make proto`"
        f"（期望文件：{GEN_DIR}/rag_service_pb2.py）"
    ) from exc

__all__ = ["pb2", "pb2_grpc", "GEN_DIR"]
