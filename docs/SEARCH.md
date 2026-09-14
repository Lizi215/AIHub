# 搜索与 MySQL↔ES 一致性

**MySQL 是唯一事实来源（Source of Truth），ES 是可随时重建的派生索引。**

## 1. 一致性方案

### 禁止的做法

- 禁止同步双写，即一次请求里同时写 MySQL 与 ES。ES 抖动会直接导致业务写入失败，可用性被耦合，同时拖慢接口响应。
- 禁止用 Canal 订阅 binlog。它引入新的中间件与运维成本，违背「不为堆技术而增加中间件」的原则。

### 采用的方案

本地消息表（Outbox）+ MQ + 幂等消费 + 版本防乱序 + 定时补偿 + 定期对账

**写入路径**（以 Asset Service 为例）：

```text
1. 开启本地事务
2. 写业务表（asset / asset_version）
3. 写 event_outbox            ← 与业务表在同一事务内，保证原子
4. 提交事务
5. 提交后立即尝试投递到 RocketMQ
6. 投递成功 → outbox 标记 SENT；失败 → 留给定时任务重投
```

业务数据与「待投递事件」在同一事务内落库，从根上解决「业务写成功但消息没发出去」。

**消费路径**（Search Service）：

```text
1. 消费消息（消息体必须含 eventId 与 opVersion）
2. 幂等校验：Redis SETNX eventId，已处理过直接 ACK
3. 建 / 更新 / 删除 ES 文档
4. 失败 → 交给 RocketMQ 重试
5. 重试耗尽 → 进入死信队列，由定时任务重试并告警
```

同一 Asset 的多次变更可能乱序到达。ES 文档必须携带 `opVersion`（单调递增），写入前比对版本，丢弃比当前文档更旧的事件。

**兜底机制**：

| 机制 | 作用 | 频率 |
|---|---|---|
| outbox 重投 | 补投未发出的消息 | 定时（分钟级） |
| 死信重试 | 补处理消费失败的消息 | 定时（分钟级） |
| **对账任务** | 以 MySQL 为准，按 `updated_at` 增量比对记录在 ES 中是否存在且版本一致，补齐差异 | 定时（小时 / 天级） |

> 这里达成的是最终一致，不是强一致；但任一环节失败都有补偿路径。

### outbox 的适用范围

只有「持有业务数据、且需要业务写与事件发保持原子」的服务才需要 `event_outbox`：

- 需要：Asset Service、Community Service
- 不需要：File Service。它无状态，没有业务写入需要与事件保持原子，下载完成后直接发 MQ 即可；下载统计属非关键操作，允许极少量事件丢失。

### 哪些变更需要同步

只有 `status = PUBLISHED` 的 Asset 才建索引：

| 变更 | 对 ES 的动作 |
|---|---|
| 发布（DRAFT → PUBLISHED） | 建 / 更新索引 |
| 更新（PUBLISHED 且内容变化） | 更新索引 |
| 下架 / 删除（→ SUSPENDED） | **删除 ES 文档** |

草稿态 Asset 绝不允许出现在 ES 中，这是防止草稿泄漏的第一道防线。

## 2. 索引、Mapping 与重建

**按 doc_type 分索引**，每个索引配别名，用别名完成零停机重建：

```text
aihub_asset_v1   ← alias: aihub_asset_write / aihub_asset_read
aihub_post_v1    ← alias: aihub_post_write  / aihub_post_read
aihub_issue_v1   ← alias: aihub_issue_write / aihub_issue_read
```

- 读写一律走别名，代码中禁止出现带版本号的物理索引名。
- 中文分词统一使用 IK 分词器：索引时 `ik_max_word`，查询时 `ik_smart`。
- 需要精确匹配、排序或聚合的字段必须同时具备 `keyword` 类型（或用 `fields` 子字段）。

**Asset 索引字段约定**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | keyword | Asset ID（已序列化为 String） |
| `title` | text(`ik_max_word`) + keyword 子字段 | 子字段用于排序与聚合 |
| `description` | text(`ik_max_word`) | |
| `readme` | text(`ik_max_word`) | |
| `tags` | keyword（数组） | 标签筛选与聚合 |
| `category` | keyword | 分类筛选 |
| `authorId` | keyword | 作者过滤（term 精确匹配，前端点作者名"只看该作者"用） |

> `authorName` 不进索引。昵称在搜索响应组装时由 Search Service 调
> `UserQueryApi` 批量解析后补进 `source`（2026-09-28 决策：动映射就要重建索引，
> 而昵称是纯展示字段；User 服务抖动时降级为空，前端用「用户#后 6 位」兜底）。
| `status` | keyword | 只索引 `PUBLISHED` |
| `opVersion` | long | 防乱序比对 |
| `downloadCount` | long | 天级冗余，用于热度加权（见 §3） |
| `latestVersion` | keyword | 资源当前最新版本号（2026-09-28 补：Agent 的 `search_assets` 输出契约含该字段；事件链路取自事件体 `version`，对账链路取自 `asset.latest_version`）。补充该字段后需重建索引，存量文档才有值 |
| `createdAt` / `updatedAt` | date | |

**重建索引流程**（变更 mapping 时）：

```text
1. 建新索引 aihub_asset_v2
2. 全量灌数据（从 MySQL 拉全部 PUBLISHED 的 Asset）
3. 别名原子切换到 v2
4. 删除旧索引 aihub_asset_v1
```

> 别名切换在瞬间完成，搜索零停机，不需要停服重建。

## 3. 排序与热度

热度数据在 Redis，相关度计算在 ES，两者是两套不同的东西：

| | 存储 | 用途 | 实时性 |
|---|---|---|---|
| 热门榜 | Redis ZSet | 「热门资源」列表页 | 实时 |
| 搜索排序 | ES `downloadCount` | 搜索结果的相关度加权 | 天级 |

ES 的 `downloadCount` 是天级，因为 ES 写入代价远高于 Redis，每次下载都更新文档会造成大量 segment 合并。因此下载计数在消费事件时只更新 Redis，再由定时任务天级批量回写到 ES 文档。

**搜索排序使用 `function_score`**：

```text
最终得分 = 相关度得分 × (1 + 权重 × log(1 + downloadCount))
```

- 用 `log` 抑制头部效应，避免单个超热资源垄断全部搜索结果。
- 权重走 Apollo 配置，便于调整。
- 纯相关度排序与纯热度排序都作为可选模式，由请求参数决定。

## 4. 搜索能力

支持字段：标题、描述、README、标签、作者、社区内容。

搜索支持：分页、高亮、排序、分类筛选、标签筛选。

后续可增加：中文联想搜索、综合搜索。

## 5. 实现说明（Phase 3 落地）

记录实际实现方式，与上文规格的差异也写在这里。

### 索引与别名

| doc_type | 物理索引 | 写入别名 | 读取别名 |
|---|---|---|---|
| asset | `aihub_asset_v1` | `aihub_asset_write` | `aihub_asset_read` |
| post | `aihub_post_v1` | `aihub_post_write` | `aihub_post_read` |
| issue | `aihub_issue_v1` | `aihub_issue_write` | `aihub_issue_read` |

- 启动时 `IndexBootstrap` 幂等创建缺失的索引（settings + mappings + 两个别名）。
  索引被误删后，对账任务会先重建再补数据。
- 代码里只出现别名，物理索引名只在建/删索引处出现（重建时切别名即零停机）。

### 中文分词：先用内置 `cjk`，不是 IK

规格要求 IK（`ik_max_word` / `ik_smart`），但 IK 是第三方插件，本机 ES 9.5.2 未安装
（需从 GitHub 下载对应版本的构建）。这里用 ES 内置的 `cjk` 分析器（Lucene bigram）：

- 免插件，任何环境都能跑；
- 中文可检索，比逐字的 `standard` 更接近词级匹配；
- 词边界不如 IK，相关性排序偏弱（"配置模板"会被切成多个二元组）。

切换到 IK 的步骤：改 `IndexDefinitions` 里的 analyzer → 建新索引（v2）→ 全量对账灌数据
→ 原子切别名 → 删旧索引。不需要改任何查询代码。

### 防乱序：用 ES 外部版本号，而不是"先读再写"

MQ 是并发消费的（MESSAGING.md §3 也规定使用并发消息），同一个资源的两个事件可能被不同线程同时处理。
"读 opVersion → 判断 → 写"之间存在窗口，旧事件完全可能后写成功，把索引回退到过期内容。

因此写入使用 `version_type=external` + `version=opVersion`：版本不大于已存文档时
ES 直接拒绝（409），这是原子判定。读到 409 视为"乱序旧事件"，跳过而不是报错。
消费者里的"先读一次比对"只作为减少无谓请求的快路径。

### 搜索接口

```text
GET /api/v1/search/assets?keyword=&category=&sort=&page=&size=&searchAfter=
GET /api/v1/search/posts ?keyword=&type=&sort=&page=&size=&searchAfter=
GET /api/v1/search/issues?keyword=&status=&sort=&page=&size=&searchAfter=
```

- `sort`：`relevance`（默认，`_score desc, id asc`）/ `latest`（`createdAt desc, id asc`）/
  `hottest`（`downloadCount desc, id asc`，见下）；
- 每种排序都以 `id` 兜底，保证 `search_after` 的排序键唯一（否则翻页会漏记录）；
- `from + size > 9000` 时主动拒绝并提示改用 `searchAfter`（ES 默认 `max_result_window=10000`）；
- `nextSearchAfter` 是下一页游标（Base64(JSON 数组)），到末页时为空。

### 对账任务

- 触发：定时（默认 1 小时一次、回看 24 小时）+ `POST /internal/v1/search/reconcile?updatedAfter=`
  手动触发（不传时间表示**全量**，用于索引重建）；
- 数据来源：业务服务的内部**索引数据源**契约（`AssetIndexSourceApi` / `CommunityIndexSourceApi`），
  Search **不读业务表**；
- 行为：只写"索引缺失或版本更旧"的文档（`applied >= incoming` 则跳过），
  并删除"已不该在索引里"的文档（下架 / 软删除）；
- 报告：`{scanned, upserted, skipped, deleted}`，`deleted` 只统计**真正删掉**的文档。

### 尚未实现

1. **`downloadCount` 天级回写**（§3 的计划）：ES 里该字段恒为 0，因此 `sort=hottest`
   目前退化为按 `id` 排序——排序模式合法，但结果不代表热度。需要新增"从 Redis 读计数并按天批量回写 ES"的任务。
2. **Issue 事件**：Issue 没有事件（docs/MESSAGING.md 只定义 3 个），索引由对账补齐，
   因此状态变更后要等下一次对账才反映到索引里。需要实时性时补 `IssueCreatedEvent` /
   `IssueStatusChangedEvent` 并登记到 MESSAGING.md。
