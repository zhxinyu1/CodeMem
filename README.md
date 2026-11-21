# CodeMem（码忆）

面向 Java 后端学习者的“分级记忆 + 状态管理 + 双角色协作” Agent。当前仓库已完成 MVP 五条线：对话与落库、Redis 工作记忆、Obsidian 混合检索与引用、状态版本化、Outbox 异步抽取，并补齐 **双角色工作流（Mentor + Reviewer）**、**Vue 3 前端**、**30 条黄金集评测与五组消融**、**LLM 结构化抽取器**、**memory_item 长期记忆**。

> 说明：架构方案与开发过程记录（`docs/`、`HANDOFF.md`）保留在作者本地，不随本仓库发布。

## 技术栈

- Java 21
- Spring Boot 3.5.16
- Spring AI 1.1.8（OpenAI 兼容接口）
- MySQL 8.0（会话、消息、Turn、画像）
- Redis 7（工作记忆窗口与摘要）
- PostgreSQL 16 + pgvector（知识库索引、长期记忆、向量与中文关键词检索）
- jieba-analysis 1.0.2（中文分词）
- Vue 3 + Vite + Element Plus（前端，构建产物由后端 `/web/` 托管）
- Flyway（MySQL 迁移 V1–V7）
- Docker Compose（三件套）

## 目录结构

```text
codemem/
├── src/main/java/com/codemem/
│   ├── chat/              # 对话编排、Prompt 组装、SSE
│   ├── conversation/      # Conversation / Message / Turn
│   ├── memory/working/    # Redis 工作记忆
│   ├── memory/longterm/   # memory_item 长期记忆：写入去重、失效、混合召回
│   ├── knowledge/         # 扫描、索引、混合检索、引用、PostgreSQL schema
│   ├── extraction/        # 规则 / LLM 抽取器（ExtractorRouter 切换）
│   ├── workflow/          # 双角色：Router、Blackboard、Mentor、Reviewer、预算、trace
│   ├── state/             # 状态快照、补丁、版本、diff、纠错
│   ├── outbox/            # 异步派发、重试、死信
│   ├── embedding/         # Hash 与 Spring AI 两种 embedding
│   ├── ai/                # Mock 与 Spring AI 两种 ChatEngine
│   └── profile/ common/
├── src/main/resources/
│   ├── application.yml
│   ├── db/migration/      # Flyway V1–V7
│   └── static/web/        # 前端构建产物（访问 /web/）
├── web/                   # Vue 3 + Vite + Element Plus 前端源码
├── tools/eval/            # golden.jsonl、run_eval.py、ablation.py、report.md
├── docker/postgres/init/  # 创建 pgvector 扩展
├── docker-compose.yml
└── README.md
```

## 0. 当前环境说明

本机 JDK 的 `javac` 缺少 `lib/ct.sym`，使用 `--release 21` 会报“不支持发行版本 21”。因此 `pom.xml` 里显式关闭了 `maven.compiler.release`，改用 `source/target=21`。这是环境适配，不影响代码。

IDEA 自带 Maven 路径：

```text
D:\app\learn\IntelliJ IDEA Community\plugins\maven\lib\maven3\bin\mvn.cmd
```

Docker Desktop 的 CLI 目录默认可能没有进入 PATH：

```text
C:\Users\Lenovo\AppData\Local\Programs\DockerDesktop\resources\bin
```

如果终端里 `docker` 找不到，先把上面这个目录加入用户 PATH，或者重开终端；也可以直接用完整路径。

## 1. 启动数据库

在项目根目录执行：

```bash
docker compose up -d
docker compose ps
```

期望三个服务都是 `healthy`：

```text
codemem-mysql      mysql:8.0              healthy   0.0.0.0:3306
codemem-redis      redis:7-alpine         healthy   0.0.0.0:6379
codemem-postgres   pgvector/pgvector:pg16 healthy   0.0.0.0:5432
```

不要同时启动本机 MySQL，否则 3306 会冲突。本机 MySQL 9.6 只在没有 Docker 时做临时替代。

## 2. 启动应用

### 方式 A：IDEA

用 IntelliJ IDEA 打开 `codemem/pom.xml`，等待依赖下载，运行：

```text
com.codemem.CodeMemApplication
```

默认 `CODEMEM_AI_MOCK=true` 只控制对话；embedding 默认 `EMBEDDING_PROVIDER=openai`。启动后打开 `http://localhost:8080/settings.html` 输入 API Key，Key 只保存在当前进程内存，重启后清空。完全离线时设 `EMBEDDING_PROVIDER=hash`。

### 方式 B：命令行

```powershell
& "D:\app\learn\IntelliJ IDEA Community\plugins\maven\lib\maven3\bin\mvn.cmd" spring-boot:run
```

健康检查：

```bash
curl http://127.0.0.1:8080/actuator/health
```

期望 `status: UP`，且 `db` 与 `redis` 都是 `UP`。

首次启动会在 embedding 可用时自动建知识索引；也可以手动重建：

```bash
curl -X POST http://127.0.0.1:8080/api/v1/knowledge/reindex
curl http://127.0.0.1:8080/api/v1/knowledge/status
```

期望 `documents` 约为 22，`chunks` 为两百多。状态里的 `needsReindex=true` 表示当前索引与 embedding 签名不一致，需要重建。索引只读扫描 `E:\Note\知识库`，不会修改原笔记。

## 3. 验证接口

### 学习画像

```bash
curl http://127.0.0.1:8080/api/v1/profile
```

### 知识库检索

直接用中文 URL 参数时，Windows 终端可能按 GBK 编码，导致检索不到。先用 Python 生成 UTF-8 百分号编码：

```bash
Q=$(python - <<'PY'
import urllib.parse
# 换成你知识库里真实存在的关键词，多个词用空格分隔
print(urllib.parse.quote("要检索的关键词", safe=""))
PY
)
curl "http://127.0.0.1:8080/api/v1/knowledge/search?q=${Q}&topK=5"
```

期望前排是与关键词最相关的笔记块。返回字段包括 `path`、`headingPath`、`content`、`score`。

### 非流式对话

先建一个 UTF-8 请求文件，避免 Windows 终端把中文转成 GBK：

```bash
python - <<'PY'
import json
with open('target/req.json','w',encoding='utf-8') as f:
    json.dump({"learnerId":"default","message":"我现在 Java 学到哪了？"}, f, ensure_ascii=False)
PY
curl -s -X POST http://127.0.0.1:8080/api/v1/chat \
  -H 'Content-Type: application/json; charset=utf-8' \
  --data-binary @target/req.json
```

返回包含 `conversationId`、`turnId`、`answer`、`citations`、`memoryCount`、`latencyMs`。`citations` 是本次回答实际用到的笔记块，包含 `path`、`heading`、`snippet`、`score`。

### SSE 流式对话

```bash
python - <<'PY'
import json
with open('target/stream.json','w',encoding='utf-8') as f:
    json.dump({"learnerId":"default","conversationId":1,"message":"我当前卡在什么问题上？"}, f, ensure_ascii=False)
PY
curl -N -X POST http://127.0.0.1:8080/api/v1/chat/stream \
  -H 'Content-Type: application/json; charset=utf-8' \
  --data-binary @target/stream.json
```

事件顺序：

```text
event:meta      -> conversationId / turnId / traceId / mock
event:citation  -> 本次用到的笔记块（path / heading / snippet / score）
event:token     -> 增量文本
event:done      -> assistantMessageId / latencyMs / memoryCount / citationCount
event:error     -> code / message
```

### 会话与消息

```bash
curl 'http://127.0.0.1:8080/api/v1/conversations?learnerId=default'
curl 'http://127.0.0.1:8080/api/v1/conversations/1/messages?learnerId=default'
# 删除会话：级联清理消息、轮次、抽取结果、Agent 轨迹、Outbox 事件与 Redis 工作记忆
curl -X DELETE 'http://127.0.0.1:8080/api/v1/conversations/1?learnerId=default'
```

删除接口返回各表实际删除行数，例如
`{"conversationId":97,"messages":2,"turns":1,"extractions":1,"agentRuns":1,"agentSteps":0,"outboxEvents":1}`；
归属不符返回 403，会话不存在（含重复删除）返回 404。学习者的 `state_event` 属于跨会话学习状态，不随会话删除回滚。

## 4. 验证落库与工作记忆

### MySQL

```bash
docker exec -it codemem-mysql mysql -ucodemem -pcodemem -e \
  "SELECT id, conversation_id, role, LEFT(content,40) AS preview FROM codemem.message ORDER BY id;"
docker exec -it codemem-mysql mysql -ucodemem -pcodemem -e \
  "SELECT id, status, latency_ms FROM codemem.turn ORDER BY created_at;"
```

应看到 `user` / `assistant` 成对消息，Turn 状态为 `SUCCESS`；客户端中途断开时，Turn 应为 `FAILED`，不会永久停在 `STREAMING`。

### Redis

```bash
docker exec -it codemem-redis redis-cli keys 'wm:*'
docker exec -it codemem-redis redis-cli llen wm:1:messages
docker exec -it codemem-redis redis-cli get wm:1:summary
```

默认窗口 12 条，TTL 7 天。第二轮对话的 `memoryCount` 应大于 0，说明工作记忆被真正读入 Prompt。

## 5. 对话与 embedding 的切换

`CODEMEM_AI_MOCK` 只控制对话；embedding 由 `EMBEDDING_PROVIDER` 单独控制，两者可以独立组合。

### 5.1 只切真实对话

```powershell
$env:CODEMEM_AI_MOCK="false"
$env:OPENAI_API_KEY="你的 key"
$env:OPENAI_BASE_URL="https://api.openai.com"
$env:OPENAI_CHAT_MODEL="gpt-4o-mini"
```

### 5.2 使用真实 embedding（默认）

默认就是 `EMBEDDING_PROVIDER=openai`。设置：

```powershell
$env:EMBEDDING_PROVIDER="openai"
$env:OPENAI_API_KEY="你的 key"
$env:OPENAI_BASE_URL="https://api.openai.com"
$env:OPENAI_EMBEDDING_MODEL="text-embedding-3-small"
$env:OPENAI_EMBEDDING_DIMENSIONS="1024"
```

也可以不设 Key，启动后打开：

```text
http://localhost:8080/settings.html
```

在页面里输入 Key，点“保存并重建索引”。Key 只保存在进程内存，不落盘、不回显，应用重启后需要重新输入；环境变量只作为兜底。

然后重建索引：

```bash
curl -X POST http://127.0.0.1:8080/api/v1/knowledge/reindex
```

注意：

- `OPENAI_BASE_URL` 同时供对话和 embedding 使用，当前实现要求所选服务同时支持 chat 和 embeddings；如果要“DeepSeek 对话 + 其他服务 embedding”，需要后续拆两个客户端。
- `OPENAI_EMBEDDING_DIMENSIONS` 必须和 PostgreSQL 的 `vector(1024)` 一致，不要随意改；要改维度需同时迁移表结构。
- 索引里记录了 `embedding_signature`。切换 embedding 服务、模型或维度后，状态接口会返回 `needsReindex=true`，下一次 reindex 会自动全量重建，不会继续用旧向量。
- 索引按笔记批量调用 embedding，22 篇笔记约 22 次请求，而不是按 247 个块逐块请求。
- `/settings.html` 和 `POST /api/v1/settings/embedding` 目前没有认证，只适合本机开发；部署到公网前必须加登录或反向代理保护。

### 5.3 完全离线：显式启用 hash embedding

只有显式设置才会用本地 hash：

```powershell
$env:CODEMEM_AI_MOCK="true"
$env:EMBEDDING_PROVIDER="hash"
```

hash 模式的语义能力有限，只适合没有 API Key 时跑通链路。

API Key 只放在环境变量或运行时设置页里，不要写进 `application.yml`、数据库或 Git。运行时设置页的 Key 只存在内存，不会落盘。

## 6. 状态管理与接口

### 6.1 状态模型

首次访问会从学习画像创建一个版本 0 的 `ProjectState`，保存到 MySQL `state_snapshot`：

```bash
curl http://127.0.0.1:8080/api/v1/state
curl http://127.0.0.1:8080/api/v1/state/history
curl 'http://127.0.0.1:8080/api/v1/state/diff?from=0&to=1'
```

### 6.2 应用状态补丁

状态只能通过 `StatePatch` 修改，模型不能直接覆盖完整状态。每个 op 都必须带证据：

```json
{
  "baseVersion": 0,
  "reason": "用户明确表示该主题不会手写",
  "ops": [
    {
      "op": "SET",
      "path": "currentBlocker",
      "value": {
        "topic": "示例主题",
        "symptom": "能背概念但不会手写实现",
        "attempted": ["看视频"],
        "since": "2026-11-15",
        "evidence": ["msg:1"]
      },
      "confidence": 0.86,
      "evidence": ["msg:1"]
    },
    {
      "op": "ADD",
      "path": "weakPoints",
      "value": {
        "topic": "示例主题",
        "symptom": "无法独立写出实现",
        "confidence": 0.72,
        "lastSeen": "2026-11-15",
        "evidence": ["msg:1"]
      },
      "evidence": ["msg:1"]
    }
  ]
}
```

```bash
curl -X POST 'http://127.0.0.1:8080/api/v1/state/patch?learnerId=default&projectId=default' \
  -H 'Content-Type: application/json; charset=utf-8' \
  --data-binary @state_patch.json
```

校验规则：

- `baseVersion` 必须等于当前版本，否则返回 400；
- 只允许 `goal / stage / mastered / weakPoints / currentBlocker / decisions / nextActions / openQuestions / preferences`；
- `ADD / REMOVE` 只能用于列表路径；
- 每条 op 必须有非空 `evidence`；
- 成功后版本 +1，写 `state_snapshot` 与 `state_event`。

纠错走单独接口，事件状态为 `CORRECTED`：

```bash
curl -X POST http://127.0.0.1:8080/api/v1/state/correct \
  -H 'Content-Type: application/json; charset=utf-8' \
  --data-binary @state_correct.json
```

### 6.3 接口清单

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/state` | 当前状态 |
| GET | `/api/v1/state/history` | 状态版本历史 |
| GET | `/api/v1/state/diff?from=&to=` | 两个版本的差异 |
| POST | `/api/v1/state/patch` | 应用状态补丁 |
| POST | `/api/v1/state/correct` | 纠错补丁（事件标记 CORRECTED） |
| POST | `/api/v1/chat` | 非流式对话 |
| POST | `/api/v1/chat/stream` | SSE 流式对话 |
| GET | `/api/v1/profile` | 学习画像 |
| GET | `/api/v1/conversations?learnerId=&page=&size=` | 会话列表（默认 page=0、size=50，上限 200） |
| GET | `/api/v1/conversations/{id}/messages?learnerId=&page=&size=` | 会话消息（校验归属，越权 403；默认 size=200，上限 500） |
| DELETE | `/api/v1/conversations/{id}?learnerId=` | 删除会话并级联清理派生数据（越权 403、不存在 404） |
| GET | `/api/v1/memory/items?learnerId=&projectId=&status=&limit=` | 记忆列表（`status` 默认 ACTIVE，传 ALL 看全部状态） |
| POST | `/api/v1/memory/items/{id}/archive` | 归档一条记忆（不再参与召回，保留历史） |
| DELETE | `/api/v1/memory/items/{id}` | 物理删除一条记忆（非法 UUID 400、不存在 404） |
| GET | `/api/v1/memory/items/instructions?learnerId=&projectId=` | 生效中的全局指令（按创建时间升序） |
| GET | `/api/v1/memory/items/instructions/preview` | 预览本轮注入的指令块 |
| POST | `/api/v1/memory/items/instructions` | 新增全局指令（`{content}`，重复内容 400） |
| PUT | `/api/v1/memory/items/instructions/{id}` | 修改指令内容 / 启停 |
| POST | `/api/v1/memory/items/{id}/promote` | 把已有记忆记录转为全局指令 |
| GET | `/api/v1/knowledge/sources` | 知识源列表与当前生效源 |
| POST | `/api/v1/knowledge/sources` | 注册知识源（`{name,type,rootPath,activate,reindex}`） |
| POST | `/api/v1/knowledge/sources/{id}/activate?reindex=` | 切换到指定知识源并可选重建索引 |
| DELETE | `/api/v1/knowledge/sources/{id}` | 移除知识源（当前生效源不可删，返回 400） |
| POST | `/api/v1/knowledge/pick-directory` | 由本机后端弹出系统文件夹选择框，返回所选绝对路径 |
| POST | `/api/v1/knowledge/reindex` | 只读扫描并重建知识索引 |
| GET | `/api/v1/knowledge/status` | 索引状态 |
| GET | `/api/v1/knowledge/search?q=&topK=` | 混合检索调试 |
| GET | `/api/v1/settings/embedding` | 查看 embedding 配置与 Key 状态 |
| POST | `/api/v1/settings/embedding` | 运行时输入 API Key（仅内存） |
| GET | `/api/v1/outbox/status` | Outbox 待处理/处理中/完成/死信数量 |
| GET | `/api/v1/outbox/recent` | 最近 Outbox 事件 |
| POST | `/api/v1/outbox/{id}/retry` | 手动重投一条事件 |
| GET | `/api/v1/extractions/recent` | 最近自动抽取记录 |
| GET | `/actuator/health` | 健康检查 |

### 6.4 Outbox 异步记忆

对话完成后不会在请求线程里做抽取，而是写一条 `outbox_event`：

```text
对话完成
  -> ChatService 写 TURN_COMPLETED 事件
  -> 调度器每 2 秒扫描 PENDING
  -> 规则化 TurnExtractor 生成摘要/记忆候选/StatePatch ops
  -> 写 turn_extraction
  -> StateService 校验并应用补丁
  -> 更新 state_snapshot/state_event
```

状态查询：

```bash
curl http://127.0.0.1:8080/api/v1/outbox/status
curl http://127.0.0.1:8080/api/v1/outbox/recent
curl http://127.0.0.1:8080/api/v1/extractions/recent
```

- 默认每 2 秒扫描，单批最多 5 条；
- 失败会按 `retry-backoff-seconds` 指数退避重试，默认最多 3 次，之后进入 `DEAD`；
- `PROCESSING` 超过 5 分钟会被恢复为 `PENDING`，避免进程崩溃后卡死；
- 当前抽取器是**规则版**，识别“卡在/不会/没懂”“学会了/掌握了”“下一步/计划”等信号；接真实 LLM 后可替换为结构化输出抽取器。

前端接入边界：CORS 默认允许 `http://localhost:5173` 和 `http://localhost:3000`。

### 6.5 知识库来源切换

知识库不再固定绑定 `E:\Note\知识库`，而是登记在 MySQL 的 `knowledge_source` 表里，同一时间只有一个源 `ACTIVE`。

```bash
# 看当前源和全部已登记源
curl http://127.0.0.1:8080/api/v1/knowledge/sources

# 登记一个普通 Markdown/TXT 目录并立即切换过去（activate/reindex 默认 true）
curl -X POST http://127.0.0.1:8080/api/v1/knowledge/sources \
  -H 'Content-Type: application/json' \
  -d '{"name":"工作笔记","type":"markdown","rootPath":"E:/Note/工作笔记"}'

# 切回 Obsidian 库
curl -X POST http://127.0.0.1:8080/api/v1/knowledge/sources/<id>/activate?reindex=true

# 移除（当前生效源不能删，需先切走）
curl -X DELETE http://127.0.0.1:8080/api/v1/knowledge/sources/<id>
```

设计要点：

- **单源生效**：切换时先删掉其他源的 `knowledge_doc`（chunk 由外键级联），再扫描新源写入，所以 `knowledge_doc.path` 仍是全局唯一，检索与引用校验逻辑不用改。
- **两种类型**：`obsidian` 解析 frontmatter、`[[双链]]`，只收 `.md`/`.markdown`；`markdown` 额外收 `.txt`，链接按标准 `[文本](路径)` 解析。新增来源类型只需在 `KnowledgeSourceType` 加值、在 `ObsidianScanner` 加一个分支。
- **只读**：扫描全程只读，不改原笔记；目录不存在时添加/切换直接返回 400。
- **索引归属**：`knowledge_doc.source_id` 记录来源，`/api/v1/knowledge/status` 的 `documents`/`chunks` 反映当前生效源的规模。
- 首次启动若库里还没有任何源，会用 `KNOWLEDGE_ROOT`（默认 `E:/Note/知识库`）自动建一条 Obsidian 源，老环境不用手工配置。
- 图形入口在 `http://127.0.0.1:8080/settings.html` 的「知识库来源」卡片：可查看当前源、切换、重建、移除、新增。

## 7. 当前范围与下一步

已完成：

- Docker Compose 三件套；
- Flyway 建表与画像种子；
- 多轮对话、SSE、消息与 Turn 落库；
- Redis 工作记忆窗口与摘要；
- 画像注入 Prompt；
- 对话 Mock / Spring AI 双引擎切换；
- embedding 真实 OpenAI-compatible / 本地 hash 双实现，独立配置；
- embedding 签名与切换模型后的强制重建；
- Obsidian 只读扫描、frontmatter/标题/代码块/双链解析；
- 标题切分、内容哈希、增量重建与失效文件清理；
- jieba 分词 + PostgreSQL `tsvector` 关键词检索；
- pgvector 向量检索与 RRF 混合融合；
- 非流式 `citations` 与 SSE `citation` 事件；
- `ProjectState` / `StatePatch` 状态模型；
- `state_snapshot` / `state_event` 版本与证据链；
- 状态查询、历史、diff、补丁与纠错接口；
- `outbox_event` + `@Scheduled` 异步派发、重试与死信；
- `turn_extraction` 自动抽取记录与记忆候选；
- 对话完成后自动生成并应用 `StatePatch`；
- 健康检查与统一错误处理；
- 双角色工作流：`Router` 规则路由、`Blackboard` 单轮共享、Mentor 起草 / Reviewer 审查、最多一次修订、`BudgetGuard` 预算、`agent_run` / `agent_step` 轨迹；
- SSE 追加 `agent`、`memory` 事件（既有 `meta → citation* → token* → done` 契约不变）；
- Vue 3 + Element Plus 前端：对话、引用、双角色时间线、记忆检查器、状态时间线 / diff / 纠错、知识库状态；
- 30 条黄金集 + `run_eval.py` + `ablation.py`，五组消融报告 `tools/eval/report.md`；
- LLM 结构化抽取器 `LlmTurnExtractor`（JSON 强校验、一次修复、失败回落规则版），`CODEMEM_EXTRACTOR=rule|llm` 切换；
- `memory_item` 长期记忆：去重入库、同主题失效（`SUPERSEDED` + `valid_to`）、向量 + 关键词混合召回，与笔记来源分开标注；
- 会话删除：`DELETE /api/v1/conversations/{id}` 按会话级联清理消息、轮次、抽取、Agent 轨迹、Outbox 与 Redis 工作记忆，前端会话列表带删除入口；
- 知识库来源可切换：`knowledge_source` 表 + `GET/POST /api/v1/knowledge/sources`、切换与移除接口，支持 Obsidian 与普通 Markdown/TXT 目录，`settings.html` 提供图形入口；
- 顶部导航条：主界面、记忆、设置、帮助四个页面共用入口并高亮当前页，根路径 `/` 直接落到主界面；导航栏只保留品牌与入口，身份与状态标签已移除；
- 全局指令页 `/memory.html`：只承载用户手写指令——新增 / 编辑 / 启停 / 删除 + 注入预览；系统不再自动抽取记忆，不再有优先级与排序；
- 目录选择：`POST /api/v1/knowledge/pick-directory` 由本机后端弹出系统文件夹选择框，选完回填路径，避免手抄绝对路径。

下一步（非 MVP 阻塞项）：

1. 接真实模型后补答案质量评测与引用支撑的人工抽检；
2. 长期记忆的衰减 / 遗忘曲线与手动归档接口；
3. 前端打包体积优化（Element Plus 按需引入）。

## 8. 常见问题

**Q：`docker` 命令找不到？**  
把 `C:\Users\Lenovo\AppData\Local\Programs\DockerDesktop\resources\bin` 加入用户 PATH，重开终端；或直接用完整路径。

**Q：3306 被占用？**  
本机 MySQL 可能启动了。停止本机 MySQL，或把 `docker-compose.yml` 的 `MYSQL_PORT` 改成 3307，并同步修改 `MYSQL_URL`。

**Q：中文请求报 `INVALID_JSON`？**  
Windows 终端可能把中文按 GBK 传给 curl。用上面的 Python 生成 UTF-8 文件，再 `--data-binary @file`。

**Q：中文检索总是返回不相关笔记？**  
不要用终端里的中文直接拼 URL，先按上面的 Python 示例做 UTF-8 百分号编码。默认是真实 embedding；如果你显式设了 `EMBEDDING_PROVIDER=hash`，向量是确定性 hash embedding，语义能力有限。

**Q：知识库索引为空？**  
先确认当前知识源路径可用（`GET /api/v1/knowledge/sources` 看 `active.directoryExists`，或打开 `/settings.html` 的「知识库来源」卡片），再调用 `POST /api/v1/knowledge/reindex`；状态接口的 `available` 应为 `true`。索引为 0 篇时先查源目录是否为空或路径是否写错，而不是只看 `KNOWLEDGE_ROOT`。如果 PostgreSQL 容器没起，知识功能会降级，但对话仍可用。

**Q：应用启动报 Flyway 校验失败？**  
开发阶段如果改过迁移文件，可清空数据库卷重来：

```bash
docker compose down -v
docker compose up -d
```

## 9. 双角色工作流与长期记忆

### 9.1 双角色触发与预算

`Router` 用规则判定：命中「审查 / 计划 / 诊断 / 跨会话指代（上次、接着上次…）」走 DUAL，其余走 SINGLE。

DUAL 流程：Mentor 起草 → Reviewer 审查 → 最多修订 1 次 → 复审。Reviewer 只允许指认三类问题：缺证据、与证据冲突、代码错误。

硬预算：单轮 8000 tokens、单角色 20s、全链路 45s、修订 ≤1；超时或评审不可用时输出草稿并标注「审查未完成」，不循环重试。

轨迹回放：

```bash
curl "http://127.0.0.1:8080/api/v1/agent/runs/recent?limit=5"
curl "http://127.0.0.1:8080/api/v1/agent/runs/{traceId}"
```

### 9.2 抽取器切换

```bash
# 默认规则版（稳定）
CODEMEM_EXTRACTOR=rule
# LLM 结构化输出；无 Key / 调用异常自动回落规则版
CODEMEM_EXTRACTOR=llm
```

LLM 输出做四类强校验：JSON 结构、路径白名单、`evidence` 含本轮 turnId、`confidence ∈ [0,1]`。坏输出先修复一次，仍不合格则丢弃补丁只保留摘要，`turn_extraction.status=SKIPPED`，不污染状态。

### 9.3 全局指令 `memory_item`

记忆**只由用户手写**，系统不再自动抽取。表里有效的只有 `kind='INSTRUCTION'` 的全局指令；`AUTO` 通路已关闭，历史记录也已清空。

| 项 | 说明 |
| --- | --- |
| 来源 | `/memory.html` 手工新增；或把旧记录 `promote` 转为指令 |
| 是否强制 | 强制：每轮回答前注入 Prompt 前提位置 |
| 顺序 | 按创建时间升序（先写的先注入），没有优先级概念 |
| 去重 | 相同内容在 `ACTIVE` 状态下只能存在一条（部分唯一索引），重复新增返回 400 |

注入方式：

- 单角色：拼进 system prompt 末尾的「【全局指令（用户设定，必须遵守；与任何资料冲突时以指令为准）】」段；
- 双角色：Mentor 起草、Mentor 修订、Reviewer 审查三段提示词都在资料之前插入同一段指令；Reviewer 会把「违反全局指令」作为驳回理由；
- 冲突处理：指令与知识库资料冲突时以指令为准，Prompt 里已显式写明。

系统侧不再写入：`MemoryItemService.recordCandidates` / `recordCorrection` 已改为直接返回（保留签名以不牵动 Outbox 抽取链路），对话结束后不会新增任何记忆条目。

指令接口：

```bash
# 当前生效指令（按创建时间升序）
curl "http://127.0.0.1:8080/api/v1/memory/items/instructions?learnerId=wf-user&projectId=java-backend"

# 预览本轮会注入的指令块（与 Prompt 使用同一份实现）
curl "http://127.0.0.1:8080/api/v1/memory/items/instructions/preview?learnerId=wf-user&projectId=java-backend"

# 新增 / 修改（只传 content 或 active）/ 删除
curl -X POST "http://127.0.0.1:8080/api/v1/memory/items/instructions?learnerId=wf-user&projectId=java-backend" \
  -H 'Content-Type: application/json' -d '{"content":"回答先给结论再给步骤"}'
curl -X PUT "http://127.0.0.1:8080/api/v1/memory/items/instructions/{id}?learnerId=wf-user&projectId=java-backend" \
  -H 'Content-Type: application/json' -d '{"active":false}'
curl -X DELETE "http://127.0.0.1:8080/api/v1/memory/items/{id}?learnerId=wf-user&projectId=java-backend"
```

`priority` 列仍保留在表里用于兼容，但接口不再接受、页面不再展示。

### 9.4 评测用开关

| 环境变量 | 默认 | 作用 |
| --- | --- | --- |
| `CODEMEM_FEATURE_MEMORY` | true | 工作记忆读写 |
| `CODEMEM_FEATURE_RAG` | true | 知识库检索与引用 |
| `CODEMEM_FEATURE_STATE` | true | 状态抽取与补丁 |
| `CODEMEM_FEATURE_DUAL` | true | 双角色路由 |
| `CODEMEM_AI_MOCK_EXTRACTOR_FAILURE` | false | Mock 引擎模拟「抽取模型无 Key」，用于验证回落 |

## 10. 前端界面

```bash
cd web
npm install
npm run build
# 构建产物需拷到后端静态目录后由 8080 托管
cp -r dist/* ../src/main/resources/static/web/
```

- 首页：`http://127.0.0.1:8080/` 会重定向到 `/web/`，登录进来看到的就是主界面
- 开发模式：`npm run dev`（5173，`/api` 代理到 8080）
四个页面，顶部导航直接切换：

- `对话`（`/web/`）：SSE 流式对话、会话删除；右侧检查器只保留「引用」与「知识库」两个页签（双角色 / 记忆 / 状态页签已移除，记忆统一到「记忆」页管理）
- `记忆`（`/memory.html`）：全局指令管理——新增、编辑、启停、删除，实时显示本轮注入预览；只展示你自己写的指令，系统不再自动写入；按 learnerId + projectId 分区
- `设置`（`/settings.html`）：知识库来源（当前源、切换、重建、移除、新增、「选择目录…」）与 Embedding API Key 两块
- `帮助`（`/help.html`）：CodeMem 使用教学

**顶部导航**：四个页面共用一条导航条（`码忆 CodeMem | 对话 | 记忆 | 设置 | 帮助`），当前页按 `.nav-item.active` 高亮。

- 主界面导航在 `web/src/App.vue` 的 `el-header`，样式在 `web/src/style.css` 的 `.nav` / `.nav-item`；
- `memory.html`、`settings.html`、`help.html` 各自内联同一套 `.topnav` / `.nav` 样式，改导航时四个页面一起改；
- 再加页面只需在导航里加一个 `<a class="nav-item">`，静态页直接放 `static/` 下即可（`WebUiController` 只负责 `/`、`/web` 这类入口映射）；
- 主界面导航栏只保留品牌与入口：原来的 learnerId / projectId 输入框、后端健康标签、索引规模标签已移除，避免占位；分区固定为 `wf-user` / `java-backend`，需要换分区时到记忆页或设置页改；
- 添加笔记库时，路径框旁的「选择目录…」会请求 `POST /api/v1/knowledge/pick-directory`，由本机后端弹出系统文件夹选择窗口并回填绝对路径。浏览器出于安全拿不到本地目录的完整路径，所以这一步放在同机的后端进程做（PowerShell + `FolderBrowserDialog`，不受 Spring Boot 默认 headless 影响）。

## 11. 评测与消融

```bash
# 单组（需先启动后端）
python tools/eval/run_eval.py --base http://127.0.0.1:8080 --learner eval --project java-backend

# 五组消融（脚本自动起停后端，端口 18080），输出 tools/eval/report.md
python tools/eval/ablation.py --jar target/codemem-0.1.0-SNAPSHOT.jar --out tools/eval/report.md
```

黄金集 5 类 × 6 条：笔记事实、跨会话记忆、学习规划、代码评审、矛盾纠正。E 组（完整能力）实测：Recall@5 1.0、引用关键词命中 0.833、路由准确率 1.0、Reviewer 覆盖 1.0、工作记忆召回 1.0、状态准确率 1.0、首 token p95 348.8ms（Mock 引擎）。样本量、口径与未覆盖项见报告第 4 节。

生产/正式数据不要执行 `down -v`。

## 13. 接口约定与可观测性

- **分页**：`/api/v1/conversations`、`/api/v1/conversations/{id}/messages`、`/api/v1/extractions/recent` 均支持 `page`（从 0 开始）与 `size`，超上限自动收缩（200 / 500 / 200）。
- **错误码**：参数与格式错误 400、未登录或不存在 404、越权 403、状态版本冲突 409（`VERSION_CONFLICT`）、数据唯一冲突 409（`DATA_CONFLICT`）、未预期异常 500。
- **引用校验**：回答里的 `[笔记: 路径#标题]` 会做存在性校验；路径或标题对不上时，标题缺失降级为 `[笔记: 路径]`，路径缺失替换为「（引用不可用）」，避免标题漂移被当成依据。
- **记忆去重**：`memory_item` 用部分唯一索引 `uk_memory_active_hash (learner_id, content_hash) WHERE status='ACTIVE'` 兜底并发；同一事实重复写入只加权重不新增行。
- **指标**（`/actuator/metrics/…`）：`codemem.chat.latency`、`codemem.chat.errors`、`codemem.knowledge.search.latency`、`codemem.extraction.applied|skipped|failed`。

## 12. 接真实模型（以 DeepSeek 为例）

DeepSeek 没有 embedding 接口，所以 **embedding 必须保持本地 hash**，只把对话切到真实模型：

```bash
export CODEMEM_AI_MOCK=false
export EMBEDDING_PROVIDER=hash
export OPENAI_BASE_URL=https://api.deepseek.com
export OPENAI_CHAT_MODEL=deepseek-flash
# 输出预算：400 / 800 都会把长回答截断（completion 顶格），建议 1200 起
export OPENAI_MAX_TOKENS=1200
# 关键：DeepSeek 默认开启思考模式，推理会占用输出预算，必须显式关闭
export CODEMEM_AI_THINKING=disabled
export OPENAI_API_KEY=sk-xxxx
java -jar target/codemem-0.1.0-SNAPSHOT.jar
```

**怎么确认真的切过去了**：启动日志会打印一行 `ChatEngine = SPRING_AI`（真实模型）或 `ChatEngine = MOCK`（模板）。只想快速验证时，看这一行就够，不用先发请求。

**常见坑**（都实际踩过）：

1. **IDEA 运行配置里的 `CODEMEM_AI_MOCK` 还是 `true`**：改的是系统环境变量或别的地方，程序读到的仍是 true。IDE 里要确认 `Run → Edit Configurations → Environment variables` 这一栏。
2. **设置页 `/settings.html` 的「Embedding API Key」只管向量化，不是聊天模型的 key**。聊天的 key 必须走 `OPENAI_API_KEY`，只在进程环境变量里，不落盘、不回显。
3. **Windows IDEA 的环境变量用分号分隔**：`CODEMEM_AI_MOCK=false;EMBEDDING_PROVIDER=hash;OPENAI_API_KEY=sk-...`。
4. **不设 `CODEMEM_AI_MOCK` 默认就是 Mock**（`matchIfMissing=true`），填了 key 也不会自动切真实，必须显式写 `false`。
5. 模型名只认 `deepseek-flash` / `deepseek-v4-pro`，没有 `deepseek-chat`；写错会直接 400。

已知行为与取舍：

- **思考模式必须关闭**：不关时推理 token 会吃满 `max-tokens`，导致答案为空；`CODEMEM_AI_THINKING=disabled` 会在每次请求里带 `{"thinking":{"type":"disabled"}}`。
- **真实模型下流式为「完整生成 + 分片回放」**：DeepSeek 经 Spring AI 的逐 token 映射会偶发空内容，触发兜底二次调用、成本翻倍；改成先完整生成再分片，既稳定也只计费一次。
- **成本参考**（deepseek-flash 非高峰价）：单条约 1.5k–2.7k 输入 + 60–400 输出，约 **0.002–0.01 元/条**；30 条单角色评测约 0.1 元级别。
- 每次调用都会在日志打印 `LLM usage prompt=… completion=… total=…`，可直接按官方单价核算。

实测（30 条全量，双角色开启）：Recall@5 **1.0**、引用关键词命中 **0.833**、路由准确率 **1.0**、Reviewer 覆盖 **1.0**、状态准确率 **1.0**、首 token p95 **11.6s**；30 条实际扣除 **0.13 元**（命中 prompt cache 后低于标价折算）。详细对照见 `tools/eval/report.md` 第 6 节。