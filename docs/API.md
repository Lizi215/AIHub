# API 规范

统一 REST API。

## 1. 端点示例

```text
GET    /api/v1/assets                     # 支持 category / onlyMine（登录后只看自己名下）
GET    /api/v1/assets/{id}
POST   /api/v1/assets                     # 上传阶段① 创建草稿
PUT    /api/v1/assets/{id}                # 改名称 / 简介 / 分类，仅归属者本人
DELETE /api/v1/assets/{id}                # 软删除 + 下架 + 移除索引，仅归属者本人

GET    /api/v1/assets/own-by-name?name=xxx   # 查"我自己的同名资源"（含草稿）：
#   手动上传同名资源时，前端据此决定"追加新版本"还是"新建资源"；
#   返回 {assetId, name, status, latestVersion, versions:[...]}，没有则返回 null。
#   注意：同名判断只看**当前登录用户自己**的资源——别人同名不构成冲突。

GET    /api/v1/assets/{id}/versions
GET    /api/v1/assets/{id}/versions/{version}/download   # 下载指定版本（老版本也能下）
GET    /api/v1/assets/{id}/versions/{version}/readme     # 下载该版本的 README.md（text/markdown）
POST   /api/v1/assets/{id}/versions       # 上传阶段② 上传文件
#   version 可留空：留空表示自动递增补丁号（1.0.0 → 1.0.1）；
#   资源还没有版本时用 1.0.0。当前版本不是 major.minor.patch 形态时明确报错
#   （90001 invalid parameter），要求显式指定版本号，不猜。
#   README 按版本存：一个版本一份，因此老版本的 README 也能单独下载；
#   该版本没写 README 时返回 50004，不返回一个空文件。
#   **版本不可变**：同版本号一律拒绝（90003 版本已存在），**不提供覆盖**。
#   理由：已发布版本有 SHA-256 校验和与下载记录，覆盖会让同一版本号对应两份内容、
#   无法追溯。需要替换内容时请先删除该版本再重传（或留空版本号自动递增补丁号）。
#   手动创建时的同名资源：GET /assets/own-by-name 查到后前端直接追加新版本，
#   不再新建重复资源（2026-09-30 决策）。
#   响应里的 dependencyNotes 是**上传时依赖解析**的说明（数组，可为 null）：
#   服务端读包根 aihub.yaml 的 requires 并关联依赖目标——任何人的已发布资源，
#   或本资源归属者自己的草稿（同批上传互相依赖的关键；别人的草稿是私有数据不可作依赖）。
#   未找到/自依赖/格式问题逐条回报，绝不阻断上传（2026-09-29 起）。
#   批量上传时前端按包内 requires 做拓扑排序（被依赖者先上传），见 AgentView。
#   版本列表按 create_time DESC + id DESC 排序（create_time 只到秒，需要 id 兜底保证顺序稳定）。
DELETE /api/v1/assets/{id}/versions/{version}   # 删除指定版本，仅归属者本人
#   标签：PUT /assets/{id}/tags 为**整组替换**语义；单个标签 ≤20 字符，
#   **每资源最多 10 个**（超出返回 90001），空数组表示清空。
POST   /api/v1/assets/{id}/versions/batch-delete # 批量删版本，body {versions:[...]} ≤20；
#   两者都软删版本与文件元数据（对象存储文件保留，与整资源删除一致），
#   幂等：不存在的版本跳过；返回重算后的 latest_version——
#   取剩余版本中「语义版本号」最大者（不是 createTime 序）；
#   DRAFT 删光版本 → latest 置空回到无版本草稿（可继续上传）；
#   PUBLISHED 删光版本 → 20003 拒绝（应改删整个资源）。
#   每次删除 opVersion+1 并发事件刷新 ES 的 latestVersion。
PUT    /api/v1/assets/{id}/publish        # 上传阶段③ 发布

GET    /api/v1/issues                    # 必带 assetId，按资源维度看讨论
POST   /api/v1/issues
GET    /api/v1/issues/{id}
PUT    /api/v1/issues/{id}/status
GET    /api/v1/issues/{id}/comments       # 按时间升序（对话顺序），匿名可读
POST   /api/v1/issues/{id}/comments       # 需登录

GET    /internal/v1/users/briefs          # 内部契约 UserQueryApi，不经 Gateway 暴露
GET    /internal/v1/query/assets          # 内部契约 SearchQueryApi（ES 全文检索，供 AI Agent）
GET    /internal/v1/query/community/issues # 内部契约 CommunityQueryApi
```

## 2. 版本化与文档

- 路径统一带版本前缀 `/api/v1/...`。Spring Boot 3.5 没有内置 API 版本控制能力（那是 Boot 4 的特性），必须手写。
- 使用 springdoc-openapi 生成在线接口文档，同时作为前后端字段对齐的依据。

## 3. 统一响应与错误码

统一响应体由 `aihub-common` 提供，禁止各服务自建。

```text
{
  "code": 0,
  "message": "ok",
  "data": { }
}
```

错误码分段（禁止各服务自行发明号码段）：

| 段位 | 归属 |
|---|---|
| `0` | 成功 |
| `1xxxx` | 用户域 |
| `2xxxx` | 资源域 |
| `3xxxx` | 社区域 |
| `4xxxx` | 搜索域 |
| `5xxxx` | 文件域 |
| `9xxxx` | AI 域 / 系统级 |

## 4. 分页契约

- 请求参数统一 `page`（从 1 开始）/ `size`。
- 实现统一走 MyBatis-Plus 的 `PaginationInnerInterceptor`，禁止手写 LIMIT。
- 响应字段统一为 `records` / `total` / `page` / `size`。
- ES 深分页：`from + size` 超过 10000 会直接报错，必须使用 `search_after` 或限制最大可翻页深度。

## 5. 时间、空值与 ID

| 项 | 约定 |
|---|---|
| 时间类型 | Java 端统一 `LocalDateTime`，禁止混用 `Date` / `Timestamp` / `LocalDate` |
| 时间格式 | 接口输出 ISO-8601（`yyyy-MM-ddTHH:mm:ss`），禁止时间戳与字符串混用 |
| 空值 | 全局由 `aihub-common` 的 Jackson 配置决定，禁止各服务逐字段覆盖 |
| ID 生成 | 统一雪花算法（MyBatis-Plus `IdType.ASSIGN_ID`） |
| **Long → JSON** | 雪花 ID 为 19 位，超出 JS 安全整数范围（2^53），必须全局把 `Long` / `long` 序列化为 String。前端必须按字符串处理，禁止 `parseInt` |

> 默认把 `Long` / `long` 当 ID 处理（全局序列化为字符串），只对确定不是 ID 的字段豁免为数字：
> `PageResult.total/page/size`、`LoginVo.expiresIn`、`FileUploadResponse.size`、`AssetVersionVo.size`。
> 豁免的唯一入口是 `@JsonSerialize(using = NumericLongSerializer.class)`
> （`com.aihub.common.jackson.NumericLongSerializer`），契约由 `LongSerializationContractTest` 锁定。
> 反过来（默认数字、逐个 ID 加注解）不可取：漏加一个注解会让 ID 静默丢精度，可能用错误 ID 读写他人资源。

## 6. 其他要求

- HTTP 方法语义正确
- 参数校验
- 异常统一
- 鉴权明确
- 越权校验：所有 `/{id}` 的写操作必须校验资源归属（见 `docs/SECURITY.md`）

DTO 用于请求，VO 用于响应，Entity 用于数据库映射，三者不得混用。
