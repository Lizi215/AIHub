# 配置、安全与可观测性

## 1. 配置与密钥

禁止把以下内容硬编码提交到 Git：

- 数据库密码
- Redis 密码
- MinIO Secret
- RocketMQ 认证信息
- Apollo 密钥
- AI API Key
- JWT Secret

配置优先支持环境变量：

```yaml
spring:
  datasource:
    url: ${DB_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
```

项目必须提供 `.env.example`；`.env` 不得提交 Git。日志中禁止输出敏感信息。

## 2. 鉴权实现（不引入 Spring Security）

- 鉴权统一在 Gateway 完成，业务服务不做认证。User Service 只负责登录时签发 token。
- 业务服务内使用自定义 JWT 拦截器：解析 token → 校验签名与有效期 → 将 `userId` 写入 ThreadLocal 上下文 → 请求结束后清理。
- access / refresh token 的分工、续期与注销机制见 `docs/DATA.md` §3。
- 密码哈希使用 Hutool 的 BCrypt（`cn.hutool.crypto.digest.BCrypt`）。

## 3. 安全基线

| 项 | 要求 |
|---|---|
| 密码存储 | BCrypt 哈希（Hutool），禁止 MD5 / SHA1 / 明文 |
| 越权（IDOR） | 所有 `/{id}` 的写操作必须校验资源归属；草稿态 Asset 仅 owner / Maintainer / Admin 可见。这是最常见的鉴权漏洞 |
| 登录防爆破 | 按 IP + 账号双维度限流 |
| 上传校验 | 白名单扩展名 + 文件魔数校验 + 大小上限；禁止只信任 `Content-Type` |
| 下载防刷量 | 同一用户对同一 `asset + version` 在时间窗口内只计一次下载，否则热门榜可被刷 |
| 内容 XSS | 帖子与 README 按纯文本 / Markdown 存储，禁止存储 HTML，渲染时由前端转义 |
| CORS | 在 Gateway 统一配置，使用白名单，禁止 `*` 与携带凭证同时出现 |
| 内部接口 | 跨服务接口必须校验来源，禁止直接暴露到公网 |

## 4. 可观测性

只做最小必需集：Actuator + traceId 链路 + 统一日志。

| 项 | 要求 |
|---|---|
| traceId | Gateway 生成 → Feign 透传 → RocketMQ header 透传 → 日志 MDC，全链路同一 traceId |
| 日志 | 使用 Spring Boot 默认的 Logback，统一输出格式且必须包含 traceId，统一级别规范 |
| 健康与运行时信息 | Actuator，只暴露必需端点 |
| Agent 执行日志 | 每次执行的步骤、选用的 Tool、参数与结果必须落 `agent_task`，否则出错无法复现 |

> traceId 不是可选项而是刚需：Agent 单次执行会跨多个服务与一次大模型调用，没有全链路 traceId 将无法定位问题。

明确不引入：Prometheus、Grafana、Micrometer Tracing（需额外采集端与看板）。

## 5. 部署与资源要求

全量中间件（Nacos / Apollo / Sentinel Dashboard / MySQL / Redis / ES / MinIO / RocketMQ）启动约需 8GB 内存，Docker Desktop 默认的 8GB 分配会启动失败。必须提供两套 Compose：

| 文件 | 内容 | 用途 |
|---|---|---|
| `docker-compose.core.yml` | MySQL、Redis、MinIO | 最小可运行集 |
| `docker-compose.full.yml` | core + Nacos、Apollo、Sentinel Dashboard、Elasticsearch、RocketMQ | 完整功能 |

要求：

- 所有中间件使用单机最小配置（Nacos standalone、Apollo quick start、ES 单节点、RocketMQ 单 master 无 slave）。
- README 必须写明最低硬件要求与最小启动路径。
- 每个中间件的镜像版本必须锁定，禁止使用 `latest`。
