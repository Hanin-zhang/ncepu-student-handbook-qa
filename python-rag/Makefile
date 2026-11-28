# rag-service（Python）构建入口。
#
# 用法：
#   make venv           创建 .venv（要求 Python 3.11）
#   make deps           安装 requirements.txt
#   make proto          由 ../proto/rag_service.proto 生成 app/grpc_gen/*.py，
#                       并校验两侧 protoc 版本一致（不一致则以非 0 退出）
#   make serve          启动 gRPC 服务（TLS，需先 make proto 并备好证书）
#   make smoke          对已启动的服务跑冒烟脚本
#   make test           运行 pytest
#   make clean          清理生成物与缓存
#
# 若本机 python3.11 不在 PATH（例如用 uv 安装），可显式指定：
#   make proto PY="$(uv python find 3.11)"

PY      ?= python3.11
VENV    ?= .venv
PYBIN   := $(VENV)/bin/python
PIP     := $(VENV)/bin/pip
# 优先用 venv 解释器；未建 venv 时退回系统 PY
PYRUN   := $(if $(wildcard $(PYBIN)),$(PYBIN),$(PY))

PROTO_DIR  := ../proto
PROTO_FILE := $(PROTO_DIR)/rag_service.proto
GEN_DIR    := app/grpc_gen

.PHONY: venv deps proto proto-version serve smoke test clean

venv:
	$(PY) -m venv $(VENV)

deps: venv
	$(PIP) install -q --upgrade pip
	$(PIP) install -q -r requirements.txt

proto:
	@mkdir -p $(GEN_DIR)
	$(PYRUN) -m grpc_tools.protoc -I$(PROTO_DIR) \
		--python_out=$(GEN_DIR) \
		--grpc_python_out=$(GEN_DIR) \
		$(PROTO_FILE)
	@echo "✅ 已生成 $(GEN_DIR)/rag_service_pb2.py 与 $(GEN_DIR)/rag_service_pb2_grpc.py"
	@$(MAKE) --no-print-directory proto-version

# 两侧 protoc 主版本必须一致（NFR-16 / Q-22）；不一致时构建失败。
# 版本规范化规则由 app/proto_version.py 单点实现，pytest 复用同一模块。
proto-version:
	@$(PYRUN) -m app.proto_version

# 证书路径由 .env 或环境变量提供；默认指向仓库根的 deploy/certs（由 gen-dev-certs.sh 生成）
serve:
	$(PYRUN) -m app

smoke:
	$(PYRUN) ../scripts/smoke_grpc.py

# test 依赖 proto：生成物不入库，clean clone 后直接 make test 也要能跑通
test: proto
	$(PYRUN) -m pytest -q

clean:
	rm -f $(GEN_DIR)/*.py
	rm -rf .pytest_cache
