# AI Agent 设计

Agent 是「理解意图 → 制定计划 → 调用 Tool → 分析结果 → 汇总」的执行体，不是纯聊天功能。

AI Service 不持有业务数据，Agent 不允许直接访问 MySQL / Redis / ES / MinIO，必须沿 `Agent → Tool → Business Service → Infrastructure` 链路。

## 1. 执行模型

```text
理解意图
  ↓
制定计划
  ↓
选择 Tool
  ↓
调用 Tool
  ↓
分析结果
  ↓
继续执行
  ↓
汇总结果
```

## 2. 初始 Tool 集合

| Tool | 能力 |
|---|---|
| **Search Tool** | 搜索资源 |
| **Asset Tool** | 获取详情、README、标签、版本、依赖 |
| **Download Tool** | 下载最新版 / 指定版本 / 多资源下载 |
| **Workspace Tool** | 组合 Skill / Hook / Prompt / MCP |
| **Upload Tool** | 上传前只读分析：查重、分类推断、标签建议；批量工具（analyze_upload_batch）额外透传模型在参数里撰写的 README 草稿（是否采用由用户确认）。不创建资源；"版本"能力未做（偏差见开发记录） |
| **Community Tool** | 查询 Issue / Guide / Experience / Question |
| **Tag Tool** | 给资源打标签（**写操作，CONFIRM**）：支持指定 assetIds 或"我的全部资源"，APPEND/REPLACE 两种模式 |

### 关于内部写契约

`AssetTagCommandApi`（`/internal/v1/commands/**`）是 AI 服务使用的第一个内部写接口。
边界由几项约束共同保证：ownerId 只能取自任务行（`agent_task.user_id`），模型无法指定别人的资源；
Asset 侧仍按 ownerId 做归属校验，越权直接拒绝，没有「内部调用就免检」的简化；
工具性质登记为 CONFIRM，未经用户确认，Orchestrator 会硬拒绝执行。

其余内部契约（`AssetQueryApi` 等）保持只读。

## 3. 重点功能

| 功能 | 链路 |
|---|---|
| **AI 搜索** | 搜索资源 → 获取详情 → 分析内容 → 返回结果 |
| **AI 下载助手** | 搜索 → 判断可用版本 → 生成计划 → 用户确认 → 执行下载 → 返回结果 |
| **Workspace Builder** | 搜索相关资源 → 分析资源 → 处理依赖 → 生成 Workspace → 用户确认 → 保存 |
| **AI 上传助手** | 识别类型 → 分析内容 → 提取或生成 README → 自动标签 → 查重 → 创建版本 |
| **AI Bug 助手** | 获取资源与版本 → 搜索历史 Issue → 分析问题 → 给出可能解决方案 |

> Workspace Builder 的「保存」是由 AI Service 调 User Service 落库（数据归属见 `docs/ARCHITECTURE.md` §2）。

## 4. 任务模型（异步）

Agent 任务耗时不可控（多轮 Tool Calling 可能数十秒），**禁止用同步 HTTP 请求承载**。

```text
POST   /api/v1/agent/tasks              创建任务，立即返回 taskId
GET    /api/v1/agent/tasks/{id}         查询任务状态与结果
GET    /api/v1/agent/tasks/{id}/stream  SSE 订阅执行过程（流式）
POST   /api/v1/agent/tasks/{id}/confirm 提交用户确认
DELETE /api/v1/agent/tasks/{id}         取消任务
```

创建请求体：`{scene, request, sessionId?}`。`sessionId` 为前端生成的 UUID（可空），
带上即开启多轮会话：后端把本会话最近几轮的请求与结果摘要（有界裁剪）拼进规划器提示词，
历史存 Redis（Key 含登录态 userId，TTL 2h，不持久化）；不带则行为与单轮任务一致。

`agent_task` 状态机：

```text
PENDING → RUNNING → SUCCEEDED
                 ├→ WAITING_CONFIRM → RUNNING → SUCCEEDED
                 ├→ FAILED
                 └→ CANCELLED
```

## 5. 确认机制

涉及以下操作时，**必须先进入 `WAITING_CONFIRM` 并生成计划**：

- 下载
- 批量下载
- 创建 Workspace
- 批量收藏
- 其他实际写入操作

```text
准备执行：

1. 下载 Spring Cloud Skill v2.1
2. 下载 Java Hook v1.3
3. 创建 Workspace
4. 收藏相关资源

[确认执行]
```

**实现方式**：任务挂起为 `WAITING_CONFIRM`，前端展示计划，用户调用 `/confirm` 后任务继续执行。

> **禁止用一个挂起的 HTTP 请求等待用户确认。**

其他约束：

- **Agent 不得伪造工具执行结果。**
- 任务部分失败时必须明确区分：已完成 / 失败 / 失败原因。

## 6. 会话与上下文

| 项 | 约定 |
|---|---|
| 多轮历史 | 存 Redis，设置 TTL；超出窗口的历史做摘要化 |
| 上下文长度 | 必须裁剪，**禁止把完整资源 README 无差别塞入上下文** |
| Tool 定义 | `@Tool` 的 description 必须写明**用途、参数含义、返回含义、何时不该使用**，它直接决定模型能否选对工具 |
| Tool 参数 | 必须校验，**Agent 生成的参数不可信任** |

## 7. 防护约束

| 项 | 要求 |
|---|---|
| 最大步数 | 必须有上限，超出即终止 |
| 超时 | 单任务与单次 Tool 调用都必须有超时 |
| 循环检测 | 相同 Tool + 相同参数重复调用需检测并中断 |
| **Prompt 注入** | 资源 README、帖子内容均为用户输入且会进入上下文，必须做隔离与清洗，**禁止内容中的指令影响系统行为** |
| Token 成本 | 必须有限额与降级策略 |
| **无 Key 可运行** | 必须提供 mock / 本地模型模式，保证没有 AI API Key 时项目仍可启动与演示 |

## 8. 实现说明（Phase 6 落地方式）

**执行模型落在哪里**

```text
AgentTaskController（对外 5 个接口）
   └─ AgentTaskService  → 创建/查询/确认/取消；创建与确认都只投递，不阻塞 HTTP
        └─ AgentOrchestrator（后台线程）
             ├─ AgentPlanner（MockAgentPlanner | SpringAiAgentPlanner，由 AgentPlannerRouter 按有无 api-key 选择）
             ├─ AgentToolInvoker（唯一入口：守卫 → 确认门禁 → 超时执行 → 记录）
             └─ AgentTaskStore（agent_task 读写；状态变更一律过状态机）
```

- **Tool Calling 的循环由 Orchestrator 自己驱动，而不是交给框架内部的 tool-calling 循环。**
  原因：§7 的步数上限、超时、循环检测、Token 限额必须对**每一次调用**生效，
  放在框架内部就只能靠提示词与框架默认上限，既不可测也不可解释。
  规划器只产出「调用意图」（工具名 + JSON 参数），执行一律经 `AgentToolInvoker`。
- **Tool 定义只有一份**：Spring AI 的 `@Tool` / `@ToolParam` 标注在 Bean 方法上，
   `AgentToolCatalog` 用同一批 Bean 既生成 `ToolDefinition`（真实模型链路用），
   又构建自己的名字→方法索引（mock 与执行阶段用）。
- **确认门禁是代码，不是提示词**：Tool 性质登记在 `AgentToolCatalog.NATURES`，
   未登记会让**应用启动失败**；计划步骤的 `requiresConfirmation` 以注册表为准并覆写模型自报值；
   规划阶段若模型直接要求调用需确认工具，会被转成「待确认计划」而非执行。
- **无 Key 的等价性**：mock 规划器按场景执行固定脚本（从请求里抽资源 id / 关键字），
   与真实模型链路共用同一份 Tool 定义、同一条执行链路与同一套守卫。
   实际使用哪种模式记录在 `agent_task.model`，避免把演示结果当成模型能力。

**与规格的有意偏离**（Phase 6）：

1. Search Tool 已改为 Search Service 的 ES 全文检索（`SearchQueryApi`，
   2026-09-28），不再经 Asset Service 走 MySQL `LIKE`（后者只能匹配名称与简介）。
   现在能搜到 README 正文并按相关度排序；
2. 多轮会话历史已落 Redis（2026-09-28，经用户决策，解除「Agent 不直连 Redis」
   的自我约束）：历史按 §6 原文存 Redis（Key `agent:chat:{userId}:{sessionId}`，
   LIST 每轮一条 JSON，只留最近 5 轮，TTL 2h 不持久化），进提示词只取最近 3 轮、
   每轮 request ≤200 字 / 摘要 ≤400 字（有界裁剪）；sessionId 由前端生成，
   任务行落 `agent_task.session_id` 供异步编排取用；Redis 不可用时降级为无历史，
   任务不受影响。用途仅限会话历史缓存，Agent 仍不访问任何业务数据。

**新增 Tool 的检查清单**

1. 写一个实现 `AgentToolBean` 的 Bean，方法标注 `@Tool(name=…, description=…)`
   （description 写明用途、参数含义、返回含义、何时不该使用）；
2. 参数用**单个 record**，字段加 `@ToolParam` 与 Jakarta 校验注解（参数不可信，必须校验）；
3. 在 `AgentToolCatalog.NATURES` 登记性质（`READ` / `CONFIRM`），否则启动失败；
4. 返回 `ToolResult`（失败要说明原因，不可用异常代替）；
5. 读到用户上传的文本（README / 帖子正文）时，必须经 `PromptInjectionSanitizer` 清洗后再返回。
