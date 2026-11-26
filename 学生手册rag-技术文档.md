# 学生手册 RAG 智能问答系统技术文档

> 版本：v1.1  
> 更新日期：2026-11-26  
> 架构定位：Java + Python 双服务器部署 · RAG 智能问答 · 跨语言 gRPC 通信 · 服务认证与私有网络增强
>
> 本次修订：明确一期实施范围——Spring Security 最小化使用、Redis 作为可选增强、一期仅支持 Markdown、一期不引入 WireGuard 但必须启用 TLS + HMAC。

---

## 1. 方案核心结论

本系统采用 **Java 业务服务 + Python RAG 服务 + gRPC 内部通信** 的总体方案。

### 1.1 一期实施基线（重要）

> **一期不引入 WireGuard，不需要用 WireGuard 打通两个公网 IP；一期只需要能正常通过 gRPC 调用 Python RAG 服务接口即可。**
>
> 但“只用 gRPC”不等于“公网明文 gRPC”。一期仍必须配置 **TLS + HMAC-SHA256 + 安全组 IP 白名单**。gRPC 只解决调用方式，不解决加密和身份认证。

| 决策项 | 一期结论 |
|---|---|
| Spring Security | 保留，但最小化配置：stateless JWT + BCrypt + 路由/方法级角色控制；不上 OAuth2、SSO、复杂 ACL 表达式 |
| Redis | 可选增强：先用于 nonce、限流、少量缓存；Redis Streams 消息队列放二期；不作为唯一任务存储 |
| 文档格式 | 一期仅支持 Markdown；Parser 接口保持可扩展；PDF / DOCX 放二期 |
| 网络 | 一期不引入 WireGuard；使用公网 gRPC + TLS + HMAC + 安全组 IP 白名单 |
| RAG 范围 | 一期完成 Markdown 解析、章节分块、Embedding、Qdrant 向量检索、LLM 生成与引用；BM25、RRF、Reranker 放二期增强 |

### 1.2 核心结论

核心结论如下：

1. **Java 作为统一业务入口**  
   浏览器只访问 Java 服务，不直接访问 Python 服务。Java 负责用户认证、权限、会话、文档权限、历史记录、审核、审计日志和限流。

2. **Python 专注 RAG 与大模型能力**  
   Python 服务负责文档解析、文本分块、Embedding、向量检索、Skill 加载、LLM 调用和答案生成。BM25、RRF、Reranker 作为二期增强能力接入。

3. **gRPC 是 Java 与 Python 之间唯一的 AI 能力调用接口**  
   使用 Protobuf 统一定义 Java 与 Python 之间的数据结构，并支持流式问答响应和流式文档上传。

4. **gRPC、HMAC、TLS、WireGuard 的职责不同**  
   - gRPC：解决服务之间如何调用接口。
   - HMAC-SHA256：验证请求是否来自持有正确密钥的服务。
   - TLS / mTLS：加密通信，并验证通信对端身份。
   - WireGuard：在两台服务器之间建立加密私有网络。

5. **重要安全结论**  
   **gRPC 不要求必须使用 WireGuard；HMAC 不能代替 TLS。**  
   建议先完成 `gRPC + TLS + HMAC`，后续再把 WireGuard 作为网络安全增强功能接入。

6. **WireGuard 是最终推荐项，但不是第一阶段前置条件**  
   分阶段实施更容易排查网络问题。最终推荐引入 WireGuard，将 gRPC 和数据库访问切换到私有网络，隐藏 Python 服务的公网入口。

---

## 2. 整体架构

### 2.1 架构图

```text
前端层｜Vue 3

学生问答端 · 管理后台 · 文档上传 · 历史记录

HTTPS / REST API / SSE


Java 业务服务器
2 核 2GB

Spring Boot · Java 17/21
用户认证 / RBAC
问答会话管理
文档管理 / 权限
历史记录 / 审核
审计日志 / 限流
gRPC 客户端


gRPC + TLS + HMAC-SHA256

可选：WireGuard 私有隧道（二期）


Python AI 服务器
大内存服务器

Python 3.11+ · gRPC Server
文档解析 / 清洗（一期仅 Markdown）
文本分块 / Embedding
向量检索
BM25 / Reranker（二期）
Skill 加载 / 提示词
LLM 调用 / 答案生成


AI 数据组件
Qdrant 向量数据库
原始文档存储
Embedding 模型
Reranker（可选）
```

### 2.2 架构边界

- 浏览器永远只访问 Java 服务。
- Java 服务负责系统统一业务入口。
- Python 服务隐藏在内部通信层之后。
- Qdrant、原始文档、Embedding 模型由 Python 服务独立管理；Reranker 作为二期增强项接入。
- MySQL 等业务数据逻辑上由 Java 管理，Python 不直接修改 Java 的用户、权限和会话表。
- Skill 存放在 Python 服务中，由 SkillLoader 读取，不存入 Qdrant。

---

## 3. 技术栈清单

### 3.1 总体技术栈

| 层次 | 技术 |
|---|---|
| 前端 | Vue 3、TypeScript、Element Plus |
| Java 业务层 | Spring Boot、Spring Security（最小化）、MyBatis-Plus、MySQL、gRPC Java、Protobuf、SSE |
| Python AI 层（一期） | Python、grpc.aio、Markdown 解析器、BGE Embedding、Qdrant、LLM API、Agent Skill |
| Python AI 层（二期） | PyMuPDF、python-docx、BM25、RRF、BGE Reranker |
| 缓存与队列（可选） | Redis（一期用于 nonce / 限流 / 缓存；Streams 二期） |
| 安全与部署 | HMAC-SHA256、TLS / mTLS、Docker Compose、Nginx；WireGuard 二期 |

---

## 4. Java 业务服务设计

### 4.1 技术选型

| 模块 | 推荐技术 | 主要职责 |
|---|---|---|
| 基础框架 | Spring Boot 3.x + Java 17/21 | 业务服务主体 |
| 权限认证 | Spring Security + JWT（最小化配置） | 登录认证、RBAC、401/403 |
| 密码哈希 | BCryptPasswordEncoder | 密码安全存储 |
| 持久层 | MyBatis-Plus | 用户、文档、问答数据 |
| 关系数据库 | MySQL 8 | 用户、会话、消息、审核记录 |
| 服务通信 | gRPC Java + Protobuf | 调用 Python RAG |
| 流式响应 | Spring MVC SSE | 把 AI 生成内容推送给浏览器 |
| 反向代理 | Nginx | HTTPS、请求转发 |
| 接口限流 | 自定义限流 / Redis（可选） | 控制请求频率 |
| 缓存 / 轻量队列 | Redis（可选） | nonce、限流、缓存；Streams 二期 |

### 4.2 职责范围

Java 服务主要做业务编排，不承担重 AI 计算。

Java 服务应负责：

- 登录认证与 JWT 签发、校验。
- 用户、角色、权限和文档访问控制。
- 问答会话管理。
- 问答历史记录持久化。
- 文档元数据和审核状态管理。
- 审计日志记录。
- 请求限流和基础安全校验。
- 调用 Python gRPC 接口。
- 将 Python 流式返回结果通过 SSE 转发给浏览器。

Java 服务不应负责：

- PDF 解析。
- Word 解析。
- 文本向量化。
- 向量检索。
- Reranker 重排。
- 大模型调用。

### 4.3 资源约束

Java 服务器配置较低，为 **2 核 2GB**，因此资源规划要谨慎：

- 建议只运行 Nginx 和 Java 应用。
- MySQL 放到大内存服务器或使用云数据库。
- 不要在这台机器上部署 Ollama、Qdrant 和 MySQL / Redis 全家桶。
- Redis 不是一期核心链路的必需品；如果引入，优先放到大内存服务器或使用云 Redis，不要和 Java 进程抢 2GB 内存。
- Spring Security 对内存影响很小，2GB 的主要压力来自 MySQL、Redis、JVM 和模型服务，而不是安全框架。

### 4.4 Spring Security 使用边界

**结论：建议保留 Spring Security，但只使用最小能力。** 它不是 RAG 的刚需，而是业务权限层的现成方案。

项目中有以下功能会依托它：

- 登录接口的密码校验：`BCryptPasswordEncoder`。
- JWT 解析后写入 `SecurityContext`。
- `/api/admin/**` 等管理端接口的角色控制。
- 普通用户、审核员、管理员等角色校验。
- 统一返回 401 / 403。
- CORS 与基础安全响应头。
- 方法级权限：`@PreAuthorize("hasRole('ADMIN')")`。
- 审计日志中获取当前登录用户。

需要注意：**Spring Security 解决的是接口级和角色级权限，不自动解决“这个用户能不能看这份文档”。** 文档级 ACL 仍需在 Service 层实现。

实现难度：

| 范围 | 难度 | 说明 |
|---|---|---|
| 最小 JWT + 路由级角色 | 低到中 | 配置 `SecurityFilterChain`、JWT Filter、401/403 |
| 完整 RBAC + 文档级权限 | 中 | 真正的难点是权限模型和 ACL 表设计 |
| OAuth2 / SSO | 高 | 一期不建议 |
| 自研 JWT 拦截器 | 起步低，维护风险中 | 安全细节需要自己补齐 |

如果不用 Spring Security，可以选择：

- **Sa-Token**：更轻，适合小项目快速实现登录和 RBAC。
- **自研 JWT + HandlerInterceptor + 自定义权限注解**：最轻，但需要自己处理密码哈希、401/403、CORS、上下文清理和路径匹配。

无论是否使用 Spring Security，都建议至少使用 `spring-security-crypto` 的 BCrypt 做密码哈希，不要明文存储密码。

一期最小配置建议：

- 使用 Spring Security 6 的 `SecurityFilterChain`，不要照抄已废弃的 `WebSecurityConfigurerAdapter`。
- 使用 stateless 模式，禁用 `formLogin`、`httpBasic` 和默认 Session。
- JWT 放在 `Authorization` Header 时，可以关闭 CSRF；如果放 Cookie，则不能随意关闭。
- 显式配置 CORS，避免 Vue 预检请求被拦截。
- 只对必要接口启用 `@PreAuthorize`，不要一开始做复杂权限表达式。

### 4.5 Redis 使用策略（可选增强）

**结论：可以使用 Redis，但建议把“缓存”和“轻量消息队列”分开决策；一期不作为核心链路的强依赖。**

适合一期使用的场景：

| 用途 | 一期建议 | 说明 |
|---|---|---|
| HMAC nonce 防重放 | 建议 | 单实例可先用进程内 TTL 缓存；多实例时使用 Redis |
| 接口限流 | 可选 | 简单的计数器或令牌桶 |
| 权限 / 配置短期缓存 | 可选 | TTL 要短，权限变更时主动失效 |
| JWT 黑名单 | 可选 | 需要立即注销时使用 |
| 文档入库任务状态 | 可选 | MySQL 是任务状态的最终依据，Redis 只做加速 |
| Redis Streams 异步任务 | 二期 | 文档入库、审核、通知等异步解耦 |

Redis Streams 可以作为轻量消息队列，但需要额外处理：

- Consumer Group、ack 和 pending 消息。
- 消费失败重试。
- 业务幂等。
- 死信或人工补偿。
- 任务状态以 MySQL 为准，Redis 不作为唯一存储。

**聊天流式输出不要走 Redis 消息队列**，继续使用 `gRPC Server Streaming → Java SSE`。

缓存 RAG 最终答案要谨慎：如果缓存，key 必须包含用户、角色和文档版本，TTL 要短，避免越权读取或读到过期制度。

部署上，Redis 不建议放在 2 核 2GB 的 Java 服务器；优先放到大内存服务器或使用云 Redis，并且不要对公网开放 `6379`。

---

## 5. Python RAG 服务设计

### 5.1 技术选型

| 模块 | 推荐技术 | 主要职责 |
|---|---|---|
| 服务框架 | Python 3.11+ + grpcio | 对 Java 提供 gRPC 接口 |
| 协程模型 | grpc.aio | 异步 RPC 调用 |
| 接口定义 | Protobuf | 统一 Java / Python 数据结构 |
| Markdown 解析（一期） | markdown-it-py / mistune / 自定义解析器 | 仅解析 `.md`，保留标题层级、代码块、表格 |
| PDF 解析（二期） | PyMuPDF | PDF 文本提取 |
| Word 解析（二期） | python-docx | DOCX 文档解析 |
| 文本分块 | 自定义章节分块 + Token 长度控制 | 保证上下文连贯 |
| Embedding | BGE 系列 / 云端 Embedding API | 文本向量化 |
| 向量数据库 | Qdrant | 向量持久化和相似度检索 |
| 关键词检索（二期） | BM25 | 精确关键词检索 |
| 检索融合（二期） | RRF | 合并向量检索与 BM25 结果 |
| 重排序（二期） | BGE Reranker 系列 | 提高 Top-K 结果质量 |
| LLM | 通义千问 / DeepSeek 等 API | 最终答案生成 |
| Skill 管理 | 自定义 Skill Loader | 加载校园制度问答规范 |

### 5.2 一期文档格式范围：仅 Markdown

一期只实现 Markdown 解析，是为了降低文档清洗和格式兼容的复杂度，把主要精力放在分块、检索、gRPC 和问答链路上。

一期建议：

- 只接受 `.md` 文件，可选支持 `.txt`；PDF / DOCX 明确返回“暂不支持”。
- 使用 Markdown 解析器保留标题层级、代码块、表格和列表。
- 按 H2 / H3 标题分节，再按 Token 长度切分，并保留少量 overlap。
- 保留 `doc_id`、`title`、`section_path`、`version`、`source`、`permission` 等元数据。
- 定义统一的 `BaseParser` 接口，按扩展名选择解析器，二期增加 PDF / DOCX 时不用重写 RAG 流程。

如果演示材料原始格式是 PDF / Word，建议提供一个**离线 PDF → Markdown 预转换脚本**作为兜底，并尽量保留章节和页码映射，避免引用溯源信息丢失。

只支持 Markdown 是合理的，但不要把解析器写死成只能读取某一个固定文件。

### 5.3 向量数据库选择

向量数据库建议从 Chroma 调整为 **Qdrant**。

原因如下：

- Chroma 适合快速做本地原型。
- Qdrant 更适合作为独立常驻服务部署。
- Qdrant 可以独立运行，由 Python 服务单独管理。
- Java 不需要直接访问 Qdrant。
- Qdrant 管理端口不向公网开放。

### 5.4 模型策略

不建议第一版使用 Ollama 在本地运行 Qwen 8B。

原因是：

- 大内存不等于有足够的推理算力。
- 还需要考虑 CPU、GPU、模型量化和并发能力。
- 没有 GPU 时，8B 模型即使能够运行，响应也可能较慢。

第一版推荐策略：

- **Embedding**：根据服务器配置，选择本地 BGE 或云端 Embedding API。
- **Reranker**：第二阶段再增加，先保证基础检索效果。
- **LLM**：先使用云端 API，例如通义千问、DeepSeek 等。
- **模型接口**：设计成可替换的 Provider，未来想切换本地模型时不用重写整套 RAG 流程。

---

## 6. Skill 管理设计

Skill 应该放在 Python 服务中，由 Python 的 `SkillLoader` 读取，而不是存入 Qdrant。

推荐目录结构：

```text
python-rag/
├── app/
│   ├── grpc_server/
│   │   ├── rag_service.py
│   │   └── interceptors.py
│   ├── rag/
│   │   ├── parser.py
│   │   ├── chunker.py
│   │   ├── embedding.py
│   │   ├── retriever.py
│   │   ├── reranker.py
│   │   └── generator.py
│   ├── skills/
│   │   ├── campus-policy-qa/
│   │   │   ├── SKILL.md
│   │   │   └── references/
│   │   └── skill_loader.py
│   └── models/
│       └── llm_provider.py
├── proto/
│   └── rag_service.proto
├── storage/
│   └── documents/
└── requirements.txt
```

后续扩展 SSHProxy 的 Linux 命令知识检索时，可以复用 RAG 的检索能力，并独立添加 `linux-command-assistant` Skill。

需要注意：

- 校园制度知识库和 Linux 命令知识库仍然要分开。
- 不同 Skill 的顺序、提示词规范、参考资料和检索范围应独立管理。
- Skill 属于提示词与业务规范层，不应与向量库数据混在一起。

---

## 7. gRPC 接口设计

### 7.1 接口定位

gRPC 是 Java 与 Python 之间唯一的 AI 能力调用接口。

应单独维护一个 `.proto` 文件，定义 Java 和 Python 共用的数据结构。

### 7.2 初始 Protobuf 示例

```proto
syntax = "proto3";

package rag;

service RagService {

  // 问答：Python 流式返回生成内容
  rpc Chat(ChatRequest) returns (stream ChatChunk);

  // 文档入库：Java 分块上传文件
  rpc IngestDocument(stream DocumentChunk)
      returns (IngestResult);

  // 删除文档
  rpc DeleteDocument(DeleteDocumentRequest)
      returns (OperationResult);

  // 检查 RAG 服务状态
  rpc HealthCheck(HealthRequest)
      returns (HealthResponse);
}

message ChatRequest {
  string user_id = 1;
  string session_id = 2;
  string question = 3;
}

message ChatChunk {
  string content = 1;
  bool finished = 2;
}

message DocumentChunk {
  string document_id = 1;
  bytes content = 2;
  string file_name = 3;
}

message IngestResult {
  bool success = 1;
  string document_id = 2;
  string message = 3;
}
```

### 7.3 后续需要补充的字段

上述接口只是初始示意。正式实现时还需要加入：

- 文档权限信息。
- 文档版本信息。
- 文档元数据。
- 引用来源。
- 错误状态。
- 请求追踪信息。
- 明文文件摘要或内容摘要。
- 更细粒度的流式事件类型。

---

## 8. 一次问答的完整调用流程

```text
1. 用户向 Java 发送问题
   Vue 通过 HTTP 请求 Java 的 /api/chat。

2. Java 完成业务校验
   检查登录状态、用户权限、问题长度，并创建问答记录。

3. Java 调用 Python gRPC
   将问题、会话 ID 等信息通过 ChatRequest 发送给 Python。

4. Python 执行 RAG 流程
   一期：加载 Skill → 问题分析 → 向量检索 → LLM 生成。
   二期：增加 BM25 / RRF 混合检索和 Reranker。

5. Python 流式返回结果
   通过 gRPC Server Streaming 持续返回答案片段和引用信息。

6. Java 转发给浏览器
   Java 使用 SSE 将内容推送给 Vue，同时持久化问答记录。
```

通过该流程，可以实际展示：

- 跨语言 RPC 通信。
- Protobuf 数据定义。
- gRPC 流式响应。
- Java SSE 到前端的流式推送。
- RAG 检索增强生成流程。

### 8.1 文档上传流程

文档上传建议使用 **gRPC Client Streaming**。

原因是可以避免 Java 把整个文档文件一次性读入内存。

流程建议：

1. Java 接收上传文件。
2. Java 按块读取文件内容。
3. Java 通过 `IngestDocument(stream DocumentChunk)` 将文件块发送给 Python。
4. Python 边接收边写入磁盘。
5. Python 完成文件接收后执行解析、分块、Embedding 和入库。
6. Java 根据返回结果更新文档状态。

---

## 9. 两台公网服务器之间的网络安全设计

### 9.1 各技术解决的问题

| 技术 | 解决的问题 |
|---|---|
| gRPC | Java 和 Python 怎么调用彼此的接口 |
| HMAC-SHA256 | 怎么验证请求是否来自持有正确密钥的服务 |
| TLS / mTLS | 怎么加密通信，以及验证通信对端身份 |
| WireGuard | 怎么在两台服务器之间建立加密的私有网络 |

两台服务器都有公网 IP 时，可以直接进行 gRPC 通信，不需要先部署 WireGuard。

gRPC 本身支持 TLS，也支持可选的双向 TLS 客户端认证。

### 9.2 一期网络基线

一期明确**不引入 WireGuard**，但“只用 gRPC”必须理解为：

> 公网 gRPC + TLS + HMAC-SHA256 + 安全组 IP 白名单。

一期最低要求：

- Python 服务器只开放 gRPC 端口，例如 `50051`。
- 云安全组只允许 Java 服务器的固定公网出口 IP 访问该端口。
- gRPC 开启 TLS，至少完成服务端认证；mTLS 可以放到二期。
- 每次调用携带 HMAC 签名。
- MySQL、Qdrant、Redis 等管理端口不对公网开放。
- 两台服务器配置 NTP 时间同步，避免 HMAC 时间戳校验随机失败。

如果所谓“只用 gRPC”是指公网明文 gRPC，则不合理。明文 HTTP/2 在公网传输时无法保证机密性，HMAC 只能保证完整性和来源认证，不能代替 TLS。

如果两台服务器在同一个云 VPC 内，优先使用私网 IP 通信，比公网直连更简单，也比 WireGuard 更轻。

---

## 10. 网络方案 A：公网 IP 直接通信

### 10.1 拓扑

```text
Java 服务器
公网 IP A

Java gRPC Client

公网 TCP + TLS + HMAC

Python 服务器
公网 IP B

Python gRPC Server :50051
```

### 10.2 部署步骤

1. Python 服务器开放 TCP `50051`。
2. 云服务器安全组只允许 Java 服务器的公网出口 IP 访问 `50051`。
3. Python gRPC 服务开启 TLS，至少完成服务端认证；mTLS 可以放到二期。
4. 每次 Java 调用 Python 时，额外携带 HMAC 签名。
5. 数据库端口、Qdrant 管理端口、Redis 端口等都不要向公网开放。
6. 两台服务器配置 NTP 时间同步，保证 HMAC 时间戳校验稳定。

### 10.3 优缺点

优点：

- 部署简单。
- 网络链路清晰。
- 适合第一次实现 Java-Python gRPC。
- 出问题更容易排查。

缺点：

- Python 的 gRPC 服务仍然有一个公网可达入口。
- 安全性依赖防火墙、TLS、认证和访问控制的正确配置。

---

## 11. 网络方案 B：WireGuard 私有网络

### 11.1 拓扑

```text
Java 服务器

公网 IP A
wg0
10.20.0.1
Java gRPC Client


WireGuard 加密隧道

公网 UDP 51820

Python 服务器

公网 IP B
wg0
10.20.0.2
Python gRPC Server :50051



私有网络通信
```

配置完成后，Java 不再通过 Python 的公网 IP 访问 gRPC，而是访问：

```text
10.20.0.2:50051
```

### 11.2 防火墙建议

| 端口 | 协议 | 允许来源 | 用途 |
|---|---|---|---|
| 51820 | UDP | Java 服务器公网 IP | WireGuard 隧道 |
| 50051 | TCP | Java 的 WG 地址 | gRPC 服务 |
| 3306 | TCP | 指定 WG 内部地址 | MySQL（如部署在此机） |
| 任何其他管理端口 | — | 不开放公网 | 内部维护 |

这样外部用户不能直接访问 Python 的 gRPC 端口，Java 只能通过私有隧道调用它。

### 11.3 推荐策略

建议分阶段实施：

| 阶段 | 网络方案 | 目的 |
|---|---|---|
| 第一阶段 | 公网 IP + TLS + HMAC + IP 白名单 | 先把 gRPC 调用打通 |
| 第二阶段 | 加入 WireGuard | 学习两台云服务器组建私有网络 |
| 第三阶段 | gRPC 改用 WG 私有 IP | 隐藏 Python 服务公网入口 |
| 第四阶段 | 加入 mTLS、密钥轮换、访问审计 | 提高服务间安全性 |

最终推荐引入 WireGuard，因为它不仅可以保护 Python 的 gRPC 接口，还可以在 MySQL 部署到大内存服务器时，让 Java 通过同一个私有网络访问数据库，而不需要把数据库暴露到公网。

但 WireGuard 不是 gRPC 的前置条件，先不引入它会更容易排查网络问题。

### 11.4 一期是否实施 WireGuard

**一期不实施本节内容。** WireGuard 作为二期网络安全增强项，用于：

- 关闭 Python gRPC 的公网入口。
- 将 gRPC 调用切换到 `10.20.0.2:50051`。
- 将 MySQL 访问切换到私有网络。
- 配合 mTLS、密钥轮换和访问审计。

一期只需要保证公网 gRPC 在 TLS、HMAC 和安全组 IP 白名单保护下可正常调用。

---

## 12. HMAC-SHA256 服务认证设计

### 12.1 设计原则

HMAC 是消息认证码，不负责加密。

签名不能替代 TLS 的传输加密，因此 Java 与 Python 之间仍然必须使用 TLS 或 mTLS。

HMAC 只用于 Java 和 Python 之间，不能把 Secret 下发到 Vue 前端。

### 12.2 gRPC Metadata 字段

每次 Java 调用 Python 时，建议携带以下 Metadata：

| 字段 | 作用 |
|---|---|
| `x-key-id` | 标识使用哪一把密钥 |
| `x-timestamp` | 当前请求时间戳 |
| `x-nonce` | 一次性随机字符串 |
| `x-request-id` | 请求唯一标识 |
| `x-signature` | HMAC-SHA256 签名结果 |

### 12.3 签名内容

签名内容可以设计为 HTTP-like canonical string：

```text
service_method
timestamp
nonce
request_id
payload_digest
```

签名计算方式：

```text
signature = HMAC-SHA256(secret, canonical_string)
```

### 12.4 Python 校验流程

Python 收到请求后：

1. 根据 `x-key-id` 查找对应的密钥。
2. 检查时间戳是否在允许误差范围内，例如前后 60 秒。
3. 检查 `x-nonce` 是否已经使用过。
4. 重新计算 HMAC-SHA256。
5. 使用恒定时间比较函数验证签名，例如 Python 的 `hmac.compare_digest()`。
6. 只有全部通过才执行业务逻辑。

### 12.5 防重放设计

时间戳、nonce 和签名覆盖内容都很重要。

为降低重放风险，需要：

- 使用唯一 nonce。
- 设置时间窗口限制。
- 确保签名覆盖足够的请求内容。
- 对 nonce 做 TTL 缓存。
- 多实例部署后，将 nonce 缓存迁移到 Redis。

### 12.6 实现细节

- Secret 不能写入 Git 仓库，要通过环境变量或密钥配置管理。
- Nonce 可以先用 Python 进程内的带 TTL 缓存；一期单实例足够，多实例或已启用 Redis 时再迁移。
- HMAC 密钥支持 key-id，方便后期轮换。
- 大文件上传可先约定文件 SHA-256 摘要，上传完成后校验文件摘要。
- 时间戳验证需要保证两台 Linux 服务器时间同步。
- 日志中不应打印 Secret、Signature 明文或完整敏感 Metadata。

---

## 13. 最终推荐部署拓扑

### 13.1 服务分布

| 部署位置 | 服务 | 资源优先级 |
|---|---|---|
| 小服务器 | Nginx | 低 |
| 小服务器 | Spring Boot | 中 |
| 大服务器 | Python gRPC Server | 高 |
| 大服务器 | Qdrant | 中 |
| 大服务器或云数据库 | MySQL | 中 |
| 大服务器 | Embedding 模型 | 视 CPU / GPU 而定 |
| 大服务器或云 API | LLM | 优先云端 API |
| 大服务器或云 Redis | Redis（可选） | 低；一期仅 nonce / 限流 / 缓存 |
| 两台服务器 | WireGuard（二期） | 低 |

### 13.2 数据归属

- MySQL 可以放在大内存服务器或云数据库上。
- 逻辑上 MySQL 业务数据仍由 Java 管理。
- Python 不直接修改 Java 的用户、权限和会话表。
- Qdrant 由 Python 独立管理。
- 原始文档存储在 Python 服务的 `storage/documents/` 下。

### 13.3 Docker Compose 建议

建议采用两份独立的 Docker Compose：

```text
Java 服务器：
- nginx
- java-app

Python 服务器：
- rag-service
- qdrant
- mysql（若不使用云数据库）
- redis（可选；也可使用云 Redis）
```

一期不包含 WireGuard 服务，WireGuard 在二期加入。

不需要一开始就引入 Kubernetes。

也不需要为了两个服务搭建完整的微服务注册中心。

---

## 14. 分阶段实施计划

### 14.1 一期：最小可运行闭环

一期目标是完成“Markdown 文档入库 → 向量检索 → 流式问答 → 引用返回”的完整链路。

| 顺序 | 任务 | 一期范围 |
|---|---|---|
| 1 | Java + Python 最小 gRPC 通信 | `Chat` 流式问答、`IngestDocument` 流式上传、健康检查 |
| 2 | Python 基础 RAG | 仅 Markdown 解析、章节分块、Embedding、Qdrant 向量检索、LLM 生成、引用来源 |
| 3 | Java 业务闭环 | 最小化 Spring Security + JWT、基础 RBAC、SSE、会话与历史记录、文档元数据 |
| 4 | 部署与安全 | 公网 gRPC + TLS + HMAC + 安全组 IP 白名单；NTP 时间同步 |
| 5 | 可选增强 | Redis 用于 nonce、限流、少量缓存；不做 Redis Streams 消息队列 |

一期明确不做：

- WireGuard。
- PDF / DOCX 在线解析。
- OAuth2 / SSO。
- 复杂文档 ACL 表达式。
- Redis Streams 异步任务。
- BM25 / RRF / Reranker。
- Kubernetes 和微服务注册中心。

### 14.2 二期：能力增强

二期在一期闭环稳定后补充：

- PDF / DOCX 解析与更复杂的文档清洗。
- BM25、RRF 混合检索和 BGE Reranker。
- Skill 规范完善、文档审核和更细粒度权限。
- Redis Streams 异步文档入库、重试和补偿。
- WireGuard 私有网络、mTLS、密钥轮换和访问审计。
- 监控、告警和性能优化。

### 14.3 推荐开发顺序

1. 先跑通本地 Java → Python 的 gRPC 问答流。
2. 再完成 Markdown 入库和 Qdrant 检索。
3. 然后补 Java 登录、会话、SSE 和历史记录。
4. 最后部署两台服务器，完成 TLS + HMAC + 安全组配置。
5. 二期再引入 PDF / DOCX、混合检索、Redis Streams 和 WireGuard。

---

## 15. 项目展示价值

按该方案完成后，项目不仅是 RAG 问答课设，还能完整展示以下工程能力：

- Java + Python 跨语言架构设计。
- Spring Boot 业务分层与最小化 Spring Security RBAC。
- gRPC / Protobuf 跨服务通信。
- Server Streaming 与 SSE 流式响应。
- RAG 文档解析、向量化、检索与生成；二期增加 BM25 / RRF / Reranker。
- Skill 驱动的提示词规范管理。
- 服务间 HMAC 认证。
- TLS / mTLS 传输安全。
- Redis 缓存、限流与 nonce 防重放（可选）。
- WireGuard 私有网络（二期）。
- 双服务器分布式部署。
- Docker Compose 编排能力。
- 数据库、向量库、模型服务的边界划分。

---

## 16. 后续开发基准

本项目后续两人开发应以本文档为基准，优先完成一期最小可运行链路：

```text
Vue → Java（最小 Spring Security + JWT） → gRPC（TLS + HMAC） → Python（Markdown 解析 / Qdrant / LLM） → Python 流式返回 → Java SSE → Vue
```

一期必须完成：

- 最小化 Spring Security + JWT + 基础 RBAC。
- Markdown 文档上传、解析、分块和入库。
- Qdrant 向量检索、LLM 生成和引用来源。
- gRPC 流式问答与 Java SSE 转发。
- 会话、历史记录和文档元数据。
- 公网部署时的 TLS + HMAC + 安全组 IP 白名单。
- Redis 可选：nonce、限流、少量缓存。

二期再补充：

- PDF / DOCX 解析。
- BM25 / RRF / Reranker。
- Redis Streams 异步任务。
- 文档审核和更细粒度权限。
- WireGuard、mTLS、密钥轮换和访问审计。
- 监控与告警。
