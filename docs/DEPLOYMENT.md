# 部署指南

把 AIHub 部署到一台服务器的操作步骤。开发 / 本地调试见根目录 [README](../README.md) 的「快速开始」。

## 1. 前置组件

| 组件 | 用途 | 版本线 |
|---|---|---|
| JDK | 运行后端 | 17 |
| MySQL | 唯一事实来源 | 8.x（5.7 亦可，DDL 兼容） |
| Redis | token 黑名单 / 限流计数 / 统计 / 会话历史 | 6.x+ |
| MinIO | 资源包对象存储（File Service 唯一出口） | — |
| Elasticsearch | 搜索索引（Search Service 唯一出口，可随时重建） | 9.x |
| RocketMQ | 事件驱动索引与统计同步 | 5.x |
| Nacos | 注册中心（可选，可关闭改用静态实例） | 3.x |
| Node.js | 构建前端 | ≥ 20.19 |

最小可运行集：MySQL + Redis + MinIO（搜索 / 消息 / 注册中心可后接）。
`deploy/docker-compose.core.yml` 与 `docker-compose.full.yml` 提供编排。

## 2. 环境变量

复制 [`.env.example`](../.env.example) 为 `deploy/.env` 并逐项填写。必填项：

| 变量 | 说明 |
|---|---|
| `JWT_SECRET` | Gateway 与所有业务服务共用同一密钥；留空会导致服务启动失败（刻意不提供默认密钥）。用 `openssl rand -base64 48` 生成 |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | MySQL 连接（连接串须显式 `serverTimezone`） |
| `REDIS_HOST` / `REDIS_PASSWORD` | Redis |
| `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` | 对象存储凭据 |
| `ES_URIS` / `ROCKETMQ_NAME_SERVER` | 搜索与消息；不用时关掉对应的 `*_ENABLED` 开关即可 |
| `AGENT_API_KEY` | 可选。不填则 AI Agent 走 mock 模式（无 Key 也能跑通完整链路） |

端口、CORS、上传上限、Feign 超时、Agent 护栏等其余变量见 `.env.example`。
密钥一律走环境变量 / Apollo，禁止硬编码或提交 Git（见 [SECURITY.md](SECURITY.md)）。

## 3. 初始化数据库

在 MySQL 上创建好库后，执行完整 DDL：

```bash
mysql -h <host> -u <user> -p <database> < sql/schema.sql
```

`sql/schema.sql` 是全量有序建表脚本（已合并 Phase 1~7 的最终形态），在全新库上执行一次即可。
表结构与 Redis Key 说明见 [DATA.md](DATA.md)。

## 4. 启动服务

方式一：Docker Compose（推荐）

```bash
cp .env.example deploy/.env     # 填好后再起
cd deploy
docker compose -f docker-compose.full.yml up -d
```

方式二：打包后运行

```bash
cd backend && mvn -DskipTests package
# 逐个：java -jar aihub-<svc>/target/aihub-<svc>-1.0.0-SNAPSHOT.jar
```

前端：

```bash
cd frontend && npm install && npm run build   # 产物在 dist/，交给 Nginx 等静态托管
```

## 5. 健康检查

| 检查项 | 说明 |
|---|---|
| `GET <服务>:<port>/actuator/health` | 组件级健康（DB / Redis 等） |
| `GET http://<gateway>:8080/api/v1/ping` | 网关 → user 链路连通 |
| `GET /api/v1/search/assets?keyword=` | 搜索链路（需 ES） |

## 6. 首个管理员

系统不预置账号：先在前端注册一个用户（默认角色 `USER`），再用 [`sql/schema.sql`](../sql/schema.sql)
末尾的 `UPDATE` 模板把它提升为 `ADMIN` / `MAINTAINER`。角色随 JWT 签发，变更后需重新登录生效。

## 7. 生产注意事项

- AI Service 不持有业务数据：只持有 `agent_task`，取数一律走 `Agent → Tool → 业务服务 → 基础设施`。
- MinIO 唯一出口是 File Service，ES 唯一出口是 Search Service：业务服务不得直连。
- ES 是可重建的派生索引：以 MySQL 为准，缺口可用对账任务补齐。
- Nacos / Apollo 可整体关闭：`NACOS_ENABLED=false` 时用静态实例（`spring.cloud.discovery.client.simple.instances.<svc>[0].uri`）替代注册中心；Apollo 同理。
- 端口：默认网关 8080，业务服务 8081~8086；全量中间件建议 ≥10GB 内存。
