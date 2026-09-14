# AIHub

面向 AI 开发者的资源仓库：集中托管 Skill / Hook / Prompt / MCP / Rules / Template，并提供版本管理、依赖关系、全文搜索、社区交流与 AI Agent 助手。定位是「小而完整、业务真实、架构清晰」，不做综合论坛或包管理器。

---

## 功能特性

- **资源托管与版本管理**：上传 zip 资源包自动建版本；同名资源追加新版本而非建重复资源；版本不可变（同版本号拒绝覆盖）；按版本下载 zip 与 README。
- **依赖关系**：资源包根目录放 `aihub.yaml` 声明 `requires`，上传时自动解析并关联依赖；提供依赖树、循环检测；批量上传按依赖拓扑排序（详见下文「资源包约定」）。
- **标签与搜索**：标签体系（每资源 ≤10 个、每个 ≤20 字）；Elasticsearch 全文检索 + 高亮、按分类 / 标签 / 作者筛选、相关度 / 最新 / 最热排序、`search_after` 深分页；MySQL 为唯一事实来源，ES 可随时重建。
- **社区**：帖子（经验 / 指南 / 提问）、评论、收藏，以及资源 Issue 追踪与状态流转。
- **AI Agent 助手**：对话式完成资源搜索、下载、Workspace 组合、上传分析、讨论查询；支持多轮会话；需确认的操作（如下载）未经用户确认一律拒绝；README / 帖子正文按不可信输入做 Prompt 注入防护。支持真实模型（OpenAI 兼容端点，可指向 DeepSeek / 通义等）与无 Key 的 mock 模式。
- **权限体系**：Visitor（匿名）→ User → Maintainer → Admin 四角色递进；Admin 可下架资源、变更用户角色。

---

## 技术栈

**后端**

| 组件 | 版本线 |
|---|---|
| Java | 17 |
| Spring Boot | 3.5.x |
| Spring Cloud | 2025.0.x |
| Spring Cloud Alibaba | 2025.0.x |
| Sentinel | 1.8.x |
| Nacos（注册中心） | 3.x |
| Apollo（配置中心） | 2.x |
| MyBatis-Plus | 3.5.x（`mybatis-plus-spring-boot3-starter`） |
| Hutool | 5.8.x |
| springdoc-openapi | 2.x |
| MySQL | 8.4 |
| Redis | 7.x |
| Elasticsearch | 9.x |
| RocketMQ | 5.x |
| MinIO | — |
| Maven | 3.9.x |

**前端**：Vue 3 + Vite + **JavaScript（不使用 TypeScript）** + Vue Router + Pinia + Axios + Element Plus（按需引入）

> 实际解析版本（以 Maven 依赖树为准）：Boot `3.5.16` · Cloud `2025.0.3`
> （spring-cloud-commons `4.3.3`、gateway `4.3.5`）· SCA `2025.0.0.0` · Sentinel `1.8.9` · nacos-client `3.0.3`。

---

## 架构

```text
Vue3 ──► Gateway（路由 / JWT 校验 / 限流 / CORS / 全局异常）
              │
              ├─► User    Asset    Community    AI
              │
              ├─► File   ──► MinIO    （MinIO 唯一出口）
              └─► Search ──► ES       （ES 唯一出口）

异步统一走 RocketMQ（事件驱动索引与统计同步）
```

关键约束：

- **MinIO 唯一出口 = File Service**；**Elasticsearch 唯一出口 = Search Service**。
- **AI Service 不持有业务数据**，Agent 只能走 `Agent → Tool → 业务服务 → 基础设施`，禁止直连 MySQL / Redis / ES / MinIO。
- 服务之间只依赖 `aihub-common` 与 `aihub-api`，禁止依赖彼此的实现模块。
- 任何服务不得访问其他服务的表，禁止跨服务 JOIN，跨服务一律 OpenFeign。
- MySQL 是唯一事实来源，ES 是可随时重建的派生索引。

---

## 快速开始

### 环境要求

| 项 | 要求 |
|---|---|
| JDK | 17（`JAVA_HOME` 必须指向 17） |
| Maven | 3.9.x |
| Node.js | ≥ 20.19（Vite 8 要求 `^20.19.0 \|\| >=22.12.0`） |
| Docker | Docker Desktop（用于中间件） |
| 内存 | **最低 8GB 空闲给 Docker**；全量中间件（`full`）建议 10GB 以上 |

### 1. 准备配置

```bash
cp .env.example deploy/.env
# 按需修改 deploy/.env 中的密码等配置；.env 不得提交 Git
```

**至少必须设置 `JWT_SECRET`**：Gateway 与所有业务服务共用同一密钥，留空会导致服务启动失败
（刻意不提供默认密钥：使用默认密钥等于允许任何人伪造 token）。

```bash
# 生成一个密钥
export JWT_SECRET="$(openssl rand -base64 48)"
```

### 2. 启动中间件

```bash
cd deploy

# 最小集（MySQL / Redis / MinIO），只跑核心链路时够用
docker compose -f docker-compose.core.yml up -d

# 完整集（core + Nacos / Apollo / Sentinel Dashboard / ES / RocketMQ）
docker compose -f docker-compose.full.yml up -d
```

> 镜像 tag 是按版本线选取的常规 tag，**首次使用前建议先 `docker compose -f <file> pull` 验证**；
> 若某个 tag 不存在，改成同一版本线下的可用 tag。**禁止改用 `latest`。**

### 3. 启动后端

**生产部署**走 `deploy/docker-compose.core.yml` / `docker-compose.full.yml`。

**本地开发**（各开一个终端手动启动；首次先 `cd backend && mvn -DskipTests install`）：

```bash
cd backend
mvn -pl aihub-user       spring-boot:run
mvn -pl aihub-asset      spring-boot:run
mvn -pl aihub-community  spring-boot:run
mvn -pl aihub-gateway    spring-boot:run
```

> 无 Docker 的本地一键启动脚本（`start-local.sh` / `stop-local.sh` / 中间件脚本）
> 已随测试环境归档到项目外，不在本仓库。

### 4. 启动前端

```bash
cd frontend
npm install
npm run dev        # http://localhost:5173 ，/api 由 Vite 代理到 Gateway(8080)
```

### 5. 验证

```bash
curl http://127.0.0.1:8080/api/v1/ping
# {"code":0,"message":"ok","data":{"service":"aihub-user","instance":"8081",...}}
```

### 默认账号与权限

系统**不预置账号**：直接在前端「注册」创建用户（默认角色 `USER`）。需要管理员时，
用 [`sql/schema.sql`](sql/schema.sql) 末尾的 `UPDATE` 模板把某个用户名提升为
`ADMIN` / `MAINTAINER`（首个 Admin 只能这样写入）。角色随 JWT 签发，变更后需**重新登录**生效。

### 无中间件时的启动方式

后端支持在**没有 Nacos / Apollo** 的情况下启动，便于只验证代码改动：

```bash
NACOS_ENABLED=false APOLLO_ENABLED=false \
  java -jar aihub-user/target/aihub-user-1.0.0-SNAPSHOT.jar \
  --server.port=8081 \
  "--spring.cloud.discovery.client.simple.instances.aihub-asset[0].uri=http://127.0.0.1:8082"
```

此时服务发现改用 Spring Cloud 的 `SimpleDiscoveryClient` 静态实例，Feign 的 `lb://` 依旧可用。

---

## 目录结构

```text
AIHub/
├── docs/               # 架构 / 数据 / API / 搜索 / 消息 / 安全 / 后端 / 前端 / Agent / 交付
├── backend/
│   ├── pom.xml         # 父 POM：统一版本线、UTF-8 编码
│   ├── aihub-common/   # 统一响应体、错误码、全局异常、Jackson/时间/分页配置、traceId
│   ├── aihub-api/      # 服务间契约：Feign 接口 + 服务间 DTO
│   ├── aihub-gateway/  # 统一入口：路由、CORS、全局异常
│   ├── aihub-user/     # 用户服务
│   ├── aihub-asset/    # 资源服务（核心业务）
│   ├── aihub-search/   # 搜索服务（ES 唯一出口）
│   ├── aihub-file/     # 文件服务（MinIO 唯一出口）
│   ├── aihub-ai/       # AI 服务（Agent / Tool）
│   └── aihub-community/# 社区服务
├── frontend/
│   └── src/{api,components,layouts,router,stores,styles,utils,views}
├── deploy/             # docker-compose.core.yml / docker-compose.full.yml
├── sql/                # 数据库初始化脚本
├── .env.example
└── README.md
```

---

## 模块说明

| 模块 | 职责 | 数据归属 |
|---|---|---|
| `aihub-gateway` | 统一入口、路由、JWT 校验、CORS、限流、全局异常 | 无 |
| `aihub-user` | 注册登录、JWT 签发、下载历史、Workspace | `user`、`download_record`、`asset_favorite`、`workspace*` |
| `aihub-asset` | 资源创建/编辑/删除、版本、依赖、标签、文件元数据、下载统计 | `asset*`、`tag`、`asset_dependency`、`event_outbox` |
| `aihub-file` | 文件上传/下载/删除，MinIO 操作；**无状态，不持有数据表** | 无 |
| `aihub-search` | ES 全部读写：索引、搜索、高亮、排序、深分页 | 无（ES 为派生索引） |
| `aihub-ai` | Spring AI、Agent Orchestrator、Tool Calling | `agent_task` |
| `aihub-community` | 帖子、评论、收藏、Issue 追踪 | `post*`、`issue*`、`event_outbox` |
| `aihub-common` | 统一响应体、错误码、全局异常、Jackson/时间/分页、traceId | 无 |
| `aihub-api` | 服务间 Feign 契约与服务间 DTO | 无 |

---

## 资源包约定：用 `aihub.yaml` 声明依赖

资源之间的依赖（“用这个资源还需要先装什么”）**不需要手工维护**：在资源包根目录放一个
`aihub.yaml`，上传时服务端会自动解析并把依赖关联到已有资源。

### 怎么用

在 zip 包根目录放 `aihub.yaml`（只认包根，不模糊匹配）：

```
my-skill.zip
├── aihub.yaml      ← 依赖声明
├── SKILL.md
└── ...
```

`aihub.yaml` 支持两种写法：

```yaml
# 写法一：只声明资源名
requires:
  - save-last
  - md2docx

# 写法二：带版本约束（第一版只落库，不做约束求解）
requires:
  - name: save-last
    version: ">=1.0.0"
```

上传（新建资源 → 上传 zip）后，前端会弹出两条提示：

```
未找到可依赖的资源「不存在的资源」（须为已发布资源，或本资源归属者自己的草稿），已跳过
已解析 2 条依赖
```

### 行为细则

| 规则 | 说明 |
|---|---|
| 匹配方式 | 按**名称精确匹配**：任何人的**已发布**资源，或**本资源归属者自己的草稿**（别人的草稿是私有数据，不能作为依赖目标） |
| 同批上传 | 批量上传时前端会解析各包的 `requires`，**按依赖拓扑排序上传**：被依赖者先建，即使它此刻还是草稿也能被解析到 |
| 生效时机 | 上传文件时（含批量上传助手），无需额外操作 |
| 语义 | **全量替换**：以**最新版本**包内的声明为准。上传新版本时依赖会随新包更新；新包没有 `aihub.yaml`（或没写 `requires`）视为"空声明"，旧的依赖会被清掉并提示 |
| 失败处理 | **绝不阻断上传**：未找到、自依赖、格式错误、会成环的依赖都会被跳过并逐条回报（响应字段 `dependencyNotes`） |
| 稳定性 | 包内清单 ≤ 64KB、声明 ≤ 50 条；成环的依赖会被拒绝（依赖图无环是硬约定） |
| 版本约束 | `version` 只落库展示，暂不做约束求解 |

### 在哪里看到效果

- **资源详情 → 「依赖」Tab**：直接依赖列表 + 依赖树（含传递依赖，成环标记 `存在环`）。
  版本约束没写时展示为「最新版本」（语义就是跟随被依赖资源的最新版本）；页面会标明依赖来自哪个版本的声明
- **AI Agent**：生成 Workspace 组合草案时会**展开传递依赖**，告诉你“装这套还要带什么”

> 依赖来源统一为上传声明，不再提供手工增删依赖的接口，避免出现"口径两套、数据来源不明"。
> 相关设计见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) §8.4 与 [`docs/DATA.md`](docs/DATA.md)。

---

## 配置说明

- 所有敏感配置（数据库密码、Redis 密码、MinIO Secret、JWT Secret、AI API Key 等）**禁止硬编码或提交 Git**，统一走环境变量与 Apollo。样例见 [`.env.example`](.env.example)。
- **Nacos 只做注册中心，Apollo 做配置中心**；服务通过 `NACOS_ENABLED` / `APOLLO_ENABLED` 可整体关闭，便于本地无中间件启动。
- Feign 的 connect / read 超时**必须显式配置**，禁止使用默认值（当前由 `FEIGN_CONNECT_TIMEOUT` / `FEIGN_READ_TIMEOUT` 提供，后续迁到 Apollo）。
- 服务通过 `spring.cloud.discovery.client.simple.instances.<service>[0].uri` 可在无注册中心时指定静态实例。

---

## 文档索引

| 文件 | 内容 |
|---|---|
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | 服务职责、核心业务流程、权限模型 |
| [`docs/DATA.md`](docs/DATA.md) | 表结构、字段类型、Redis Key |
| [`docs/API.md`](docs/API.md) | 接口、响应结构、错误码、分页 |
| [`docs/SEARCH.md`](docs/SEARCH.md) | ES 索引、搜索、MySQL↔ES 同步 |
| [`docs/MESSAGING.md`](docs/MESSAGING.md) | 发消息、写消费者、重试与幂等 |
| [`docs/SECURITY.md`](docs/SECURITY.md) | 配置、密钥、鉴权边界、日志 traceId |
| [`docs/BACKEND.md`](docs/BACKEND.md) | 模块边界、MyBatis-Plus、Feign |
| [`docs/FRONTEND.md`](docs/FRONTEND.md) | 前端约定与 README 要求 |
| [`docs/AGENT.md`](docs/AGENT.md) | AI Service / Agent / Tool |
| [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md) | 部署指南：前置组件、环境变量、数据库初始化、健康检查 |

---

## 常见问题

**`java -version` 显示 1.8，是不是环境坏了？**
不是。PATH 里的 `java` 是 1.8，但 `JAVA_HOME` 指向 JDK 17，Maven 运行时使用 17。
需要 JDK 17 时用 `mvn`，或显式 `"$JAVA_HOME/bin/java"`。

**启动报端口被占用 / 服务监听了意料之外的端口？**
先检查环境变量 `SERVER_PORT`（或类似变量）。Spring Boot 的宽松绑定会让它覆盖 `application.yml` 里的 `server.port`。
命令行参数优先级最高，可用 `--server.port=8081` 明确指定。

**Docker 启动中间件失败，报内存不足？**
全量中间件约需 8GB，Docker Desktop 默认配额不够。请在 Docker Desktop 中把内存调到 10GB 以上，
或先用 `docker-compose.core.yml`（仅 MySQL / Redis / MinIO）。

**镜像拉取失败？**
镜像 tag 按版本线选取，未逐一验证，请 `docker compose pull` 校验；失败时替换为同一版本线下的可用 tag。
如果所在网络访问 Docker Hub 受限，请配置镜像加速器。

**前端 `npm run dev` 起来了但接口 404？**
确认 Gateway 已在 8080 且 `/api/v1/ping` 可直连；Vite 代理目标由 `frontend/.env.development` 的 `VITE_PROXY_TARGET` 控制。
