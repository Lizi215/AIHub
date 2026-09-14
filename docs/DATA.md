# 数据设计

## 1. 表清单（按归属服务分组）

```text
【User Service】
user
download_record
asset_favorite
workspace
workspace_item

【Asset Service】
asset
asset_version
asset_file
tag
asset_tag
asset_dependency
event_outbox

【Community Service】
post
post_comment
post_like
post_favorite
issue
issue_comment
event_outbox

【AI Service】
agent_task
```

**铁律：任何服务不得直接访问其他服务的表，不得跨服务 JOIN。**

设计时必须明确：主键（策略见 §2）、唯一键、索引、状态、时间字段、软删除策略。**核心字段确定后不得随意修改。**

## 2. 字段与类型规范

| 项 | 约定 |
|---|---|
| 字符集 | MySQL 统一 `utf8mb4`，否则用户昵称含 emoji 会直接报错 |
| 通用字段 | 所有业务表必须含 `id` / `create_time` / `update_time` / `deleted` |
| 主键 | 统一雪花算法（MyBatis-Plus `IdType.ASSIGN_ID`） |
| 逻辑删除 | `deleted` 为 BIGINT，`0` 表示未删除，删除时写入删除时间戳 |
| **软删除与唯一键** | 业务唯一索引必须包含 `deleted` 列。**禁止用 0/1 双态配合唯一索引**，否则资源删除后无法重建同名记录 |
| 时区 | MySQL 连接串统一 `serverTimezone`，数据库与服务器时区保持一致，禁止依赖 JVM 默认时区 |
| 资源归属 | `asset` 表必须有 `owner_id`（权限判定依赖，见 `docs/ARCHITECTURE.md` §9.2） |
| 用户角色 | `user.role` VARCHAR(20) NOT NULL DEFAULT `'USER'` |
| 依赖声明 | 资源包根目录 `aihub.yaml` 的 `requires`（`- name` 简写或 `- name/version` 映射）；上传**每个版本**时解析，全量替换该资源的直接依赖（以最新版本为准）；包里没有声明文件＝空声明（清空旧依赖）；解析不到不阻断上传 |
| README 存储 | README 属描述性元数据而非资源文件，存 MySQL（`asset_version.readme`，MEDIUMTEXT）；ES 建索引时从 MySQL 同步 |
| 审核 | **不建审核表**，用 `asset.status` 状态机表达（见 `docs/ARCHITECTURE.md` §9.4） |

### 关键字段

| 表 | 必须包含 |
|---|---|
| `asset` | `owner_id`、`status`、`category`、`latest_version` |
| `asset_version` | `asset_id`、`version`、`changelog`、`readme`、`publish_status`、`published_at` |
| `asset_file` | `asset_id`、`version_id`、`object_key`、`size`、`content_type`、`checksum` |
| `event_outbox` | `event_id`、`event_type`、`event_version`、`op_version`、`payload`、`status`、`retry_count` |
| `agent_task` | `user_id`、`status`、`plan`、`steps`、`result`、`error` |

## 3. Redis 设计

| 用途 | 结构 | Key 示例 |
|---|---|---|
| 热门资源榜 | ZSet | `aihub:hot:asset:{yyyyMMdd}` |
| 热门帖子榜 | ZSet | `aihub:hot:post:{yyyyMMdd}` |
| 点赞去重 | Set | `aihub:like:post:{postId}` |
| Refresh Token | String | `aihub:rt:{userId}:{jti}` |
| 注销黑名单 | String | `aihub:blacklist:{jti}` |
| 消息幂等 | String | `aihub:idem:event:{eventId}` |
| 限流计数 | Counter | 由 Sentinel / Gateway 维护 |

### Token 机制

配合无状态 JWT（见 `docs/SECURITY.md`）：

- **access token 不落 Redis**，无状态校验，有效期短。
- **refresh token 落 Redis**，用于续期；Redis 中不存在即视为失效，**这是支持主动注销的前提**。
- **续期即轮换（一次性使用）**：`/auth/refresh` 用 `GETDEL`（`getAndDelete`）**原子地**取出并作废
  旧 refresh token，再签发新的一对；同一个 refresh token 只能用一次。
  用 `hasKey` + `delete` 两步实现时，并发续期会「都查到、都放行」，轮换形同虚设
  （5 个并发续期全部成功），因此必须原子取出。调用方也必须做单飞续期，同一时刻只发一次续期请求。
- **注销**：access token 无法主动失效，必须将其 `jti` 写入 Redis 黑名单直至自然过期；refresh token 则直接从 Redis 删除。
- Token 与黑名单 Key 的 TTL 必须与其对应的 token 有效期一致，**禁止设置永不过期的 Key**。

### 通用约束

- Key 命名统一为 `aihub:{业务域}:{标识}`。
- 每个 Key **必须**有 TTL 或明确的清理策略，**禁止出现永久膨胀的 Key**。
- 热门榜 ZSet 必须按天分段 + 冷榜清理，不得使用单一不过期的 ZSet。
- Redis **不是唯一业务数据源**，任何数据都必须能以 MySQL 为准重建。
