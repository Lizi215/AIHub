# 架构与服务职责

## 1. Gateway

- 统一入口、路由
- **JWT 校验**（业务服务不做认证）
- 全局异常处理
- 基础限流
- CORS 统一配置

## 2. User Service

- 注册 / 登录
- 用户信息
- **JWT 签发**（登录时签发 access / refresh token）
- Asset 收藏（`asset_favorite`）
- 下载历史（`download_record`）
- Workspace（`workspace` / `workspace_item`）

Workspace 是「某个用户的资源组合方案」，生命周期跟随用户，数据归 User Service。AI Service 只生成草案，不经用户确认不得落库；依赖树解析经 Feign 调 Asset Service。

**不得承载资源、社区、AI 的核心业务。**

## 3. Asset Service

**核心业务服务。**

- Asset 创建、编辑、删除
- 资源分类
- 标签（`tag` 字典 + `asset_tag` 关联；Tag 第一版只服务 Asset）
- 资源详情、版本、状态
- 依赖关系
- 文件元数据（`asset_file`）
- 下载统计

核心对象：

```text
Asset
├── Metadata
├── Versions
├── Files
├── Tags
└── Dependencies
```

Asset Service 是资源上传流程的编排者（见 §8.1），也是业务事务的边界。File Service 只提供无状态的文件能力，不得反向编排业务。

## 4. Search Service

负责 Elasticsearch 的**全部**读写：建 / 更新 / 删除索引、全文搜索、分页、高亮、排序、条件筛选。

**Search Service 是 Elasticsearch 的唯一出口。** Asset Service、Community Service 不得直接连接 ES，必须通过 RocketMQ 事件或 Feign 交由 Search Service 处理。

索引 `doc_type` 取值：

| `doc_type` | 覆盖范围 |
|---|---|
| `asset` | Asset |
| `post` | Experience / Guide / Question（由 `post.type` 细分） |
| `issue` | Issue |

Guide、Experience 是 `post.type` 的取值，**不是独立 doc_type**。

MySQL 是业务数据源，ES 是搜索索引。详见 `docs/SEARCH.md`。

## 5. File Service

- 文件上传 / 下载 / 删除
- MinIO 操作

**File Service 是无状态的技术能力提供者，不持有任何数据表。**

- 文件元数据（`asset_file`）归 Asset Service；File Service 只负责字节流读写与 MinIO 交互。
- 对象存储路径由调用方（Asset Service）决定并传入，File Service 不解析业务语义。
- MySQL 只保存文件元数据，**绝不保存文件内容**。
- 所有对象存储操作统一通过 File Service。

资源文件路径：

```text
assets/{assetId}/{version}/package.zip
```

## 6. AI Service

- Spring AI
- Agent、Agent Orchestrator、Tool Calling
- Agent 任务状态（`agent_task`）
- Workspace Builder（只生成草案，落库归 User Service）
- AI 搜索 / 推荐 / 下载助手 / 上传助手 / Bug 分析

**AI Service 不持有业务数据。**

Agent 不允许直接访问 MySQL、Redis、ES、MinIO，必须：

```text
Agent → Tool → Business Service → Infrastructure
```

详见 `docs/AGENT.md`。

## 7. Community Service

**帖子类内容**（`post`，用 `post.type` 区分）：

- Experience / Guide / Question
- Comment（`post_comment`）
- Like（`post_like`）
- Favorite（`post_favorite`）

**问题追踪**（`issue`，独立实体）：

- Issue
- Issue Comment（`issue_comment`）

Issue 有独立状态机（Open / Processing / Closed），需关联 Asset 与 Asset Version，字段与普通帖子差异大，因此独立建模而非塞进 `post`。

- **第一版 Issue 不提供 like / favorite**（避免多态关联表）
- **第一版 Community 内容不提供标签**（Tag 只服务 Asset）

Community 是围绕资源展开的轻量社区，不做成综合论坛。

## 8. 核心业务流程

### 8.1 资源上传（三阶段）

必须拆成三个阶段。文件路径需要 `assetId`，若先传文件则 assetId 尚不存在。

**Asset Service 是编排者。**

```text
阶段 ①  创建草稿
客户端 → Gateway → Asset Service    POST /api/v1/assets
                    ↓
                 写 MySQL（asset，status = DRAFT）
                    ↓
                 返回 assetId

阶段 ②  上传文件
Asset Service → Feign → File Service → MinIO
                    ↓
                 写 asset_file + asset_version
                    ↓
                 文件路径 assets/{assetId}/{version}/package.zip

阶段 ③  发布
客户端 → Asset Service    PUT /api/v1/assets/{id}/publish
                    ↓
                 status = PUBLISHED
                    ↓
                 AssetUploadedEvent
                    ↓
                 RocketMQ
                 ├── Search Service   建 ES 索引
                 └── AI Service       自动标签（只从 tag 字典中选，不新建）
```

**草稿语义**：`status = DRAFT` 不出现在搜索结果与公开列表中。

**失败与孤儿文件处理**：

- 阶段 ② 失败 → Asset Service 捕获异常后调 File Service 删除已上传文件
- 阶段 ① 成功后长期未发布 → 定时任务清理超时草稿及其 MinIO 目录

### 8.2 资源下载

```text
用户下载
  ↓
Gateway → File Service
  ↓
MinIO 返回文件流（File Service 只做文件传输，不做业务记录）
  ↓
DownloadCompletedEvent
  ↓
RocketMQ
  ├── DownloadConsumer → User Service    写 download_record
  └── HotRankConsumer  → Asset Service   下载计数 + Redis 热门榜 ZSet
```

**每个事件消费者必须明确归属服务**，禁止出现无归属的消费者。

第一版不实现下载通知。「用户下载了资源」不构成有效业务通知。

### 8.3 资源版本

每个版本包含：版本号、更新日志、文件、发布时间、发布状态。

支持：查看历史版本、下载指定版本、获取最新版本。

### 8.4 资源依赖

Asset 可以依赖其他 Asset。第一版只做基础依赖建模与展示，不实现复杂包管理器。

必须处理：重复依赖、**循环依赖**、依赖不存在、版本约束。

## 9. 权限模型

### 9.1 角色

四个角色是**递进**关系，用 `user.role` 单字段枚举即可，**不引入 RBAC 表**。

| 角色 | 存储值 | 权限 | 增量 |
|---|---|---|---|
| Visitor | **不落库** | 浏览、搜索、下载公开资源 | —（就是「未登录」） |
| User | `USER` | 上传、收藏、发帖、管理**自己名下**的资源与 Issue | 注册即可 |
| Maintainer | `MAINTAINER` | 可处理**任意**资源的 Issue（社区版主） | 可跨归属处理 Issue |
| Admin | `ADMIN` | 审核 / 下架资源、管理社区、用户管理 | 全局管理 |

Maintainer 不负责「管自己的资源」。管理自己名下的资源由资源归属天然决定（`owner_id` 匹配即可），任何 User 都具备。

### 9.2 权限判定的两条依据

```text
允许 = 角色满足  OR  归属匹配（owner_id == 当前 userId）
```

- 「管理自己的资源」→ 走**归属**，不看角色
- 「处理他人资源的 Issue」「审核 / 下架」「用户管理」→ 走**角色**
- 权限必须在**后端**校验，前端隐藏按钮不算数

### 9.3 存储与传递

| 项 | 约定 |
|---|---|
| 字段 | `user.role` VARCHAR(20) NOT NULL DEFAULT `'USER'` |
| 首个 Admin | 由 `sql/` 下的初始化脚本写入 |
| 传递 | `role` 随 JWT 一起签发，业务服务从 token 直接读取 |

> 权衡：角色变更需重新登录才生效，第一版接受。若要求即时生效，可改为只透传 `userId`、按需向 User Service 查询并缓存到 Redis。

### 9.4 资源状态

```text
DRAFT ──发布──► PUBLISHED ──Admin 下架──► SUSPENDED
```

| 状态 | 可见性 | ES 索引 |
|---|---|---|
| `DRAFT` | 仅 owner / Maintainer / Admin | **不建索引** |
| `PUBLISHED` | 公开 | 建索引 |
| `SUSPENDED` | 公开不可见 | **移除索引** |

不做「先审后发」：发布即可见，Admin 的审核能力落在下架，因此不需要独立的审核表。
