# 消息队列（RocketMQ）

异步业务统一使用 RocketMQ。服务间**同步**调用使用 OpenFeign。

## 1. 事件与消费者

每个消费者必须标注归属服务，禁止出现无归属的消费者。

```text
DownloadCompletedEvent
├── DownloadConsumer   → User Service    写 download_record
└── HotRankConsumer    → Asset Service   下载计数 + Redis 热门榜

AssetUploadedEvent
├── IndexConsumer      → Search Service  建 ES 索引
└── TagConsumer        → AI Service      自动标签（只从 tag 字典中选）

PostCreatedEvent
└── IndexConsumer      → Search Service  建 ES 索引
```

## 2. 消息体

必须包含：

```text
eventId         消息唯一 ID（幂等用）
eventVersion    事件结构版本（schema 版本）
opVersion       业务对象版本（单调递增，防乱序用）
occurredAt      发生时间
payload         业务载荷
```

`eventVersion` 与 `opVersion` 不要混淆：

| 字段 | 含义 | 用途 |
|---|---|---|
| `eventVersion` | 事件结构版本 | 事件结构演进时做兼容 |
| `opVersion` | 业务对象版本 | 消费者比对，丢弃乱序的旧事件 |

## 3. 重试、死信与幂等参数

| 项 | 取值 | 配置项 / 说明 |
|---|---|---|
| 生产者发送重试 | 2 次 | `retryTimesWhenSendFailed` |
| 发送超时 | 3000 ms | `sendMsgTimeout` |
| 消费者最大重试 | 3 次 | `MaxReconsumeTimes`，超出后进入死信队列 |
| 重试间隔 | RocketMQ 内置递增退避 | 10s → 30s → 1m → … |
| 死信队列 | `%DLQ%{consumerGroup}` | 由定时任务扫描重投并告警 |
| 幂等 Key TTL | 24h | 必须长于最大重试窗口，否则重试会被误判为新消息 |
| 消息类型 | 并发消息 | 乱序由 `opVersion` 兜底，因此不使用顺序消息 |
| 消费线程数 | `consumeThreadMin` / `consumeThreadMax` | 默认值起步，按压测调整 |

## 4. 约束

- 事件必须定义稳定的数据结构，并包含版本字段。
- 消费者必须幂等：同一 `eventId` 重复消费只能生效一次。
- 不要为了使用 MQ 而制造没有业务意义的消息。

投递可靠性与一致性保障见 `docs/SEARCH.md` §1（Outbox 方案）。

---

## 附录：实际落地取值（Phase 3）

| 项 | 实际取值 |
|---|---|
| 资源 topic / tag | `aihub-asset-event` / `ASSET_UPLOADED` |
| 帖子 topic / tag | `aihub-community-event` / `POST_CREATED` |
| 消费者组 | `aihub-search-asset-index`（资源索引）、`aihub-search-post-index`（帖子索引） |
| 生产者组 | `aihub-asset-producer`、`aihub-community-producer`、`aihub-search-producer` |
| 客户端 | `rocketmq-spring-boot-starter:2.3.6`（`rocketmq-client 5.3.2` remoting，与 5.3.x broker 同线） |
| 消息 KEYS | 事件的 `eventId`（便于 `mqadmin queryMsgByKey` 反查） |

投递可靠性：业务提交后立即投递（`afterCommit`）+ 定时中继兜底重投。
立即投递成功会把 outbox 行标记 `SENT`（否则中继会重复投递）；
失败只记录不抛出，由中继负责重投。

`event_outbox` 一张表、两侧共用：docs/SEARCH.md §1 的适用范围把该表同时划给 Asset 与
Community（README 的模块表也如此），因此两者写入同一张表，中继只在 Asset 侧运行。
行里自带 topic/tag 与完整 payload，所以谁写入、谁中继都不影响投递正确性。
代价：Asset 不跑时，社区事件没有定时兜底（只剩各自的"提交后立即投递"）。
规模上来后应拆成每服务一张 outbox 表 + 各自中继，这条已记入开发记录。

下载事件的产生点偏离：规格写"由 File Service 在文件真正下发后产生"，
实际由 Asset Service 在 `doDownload`（字节从 File 取回后）产生。
File Service 是无状态的，没有 userId / assetId / version 这些业务上下文，
把业务字段透传给它反而破坏"不解析业务语义"的边界（docs/ARCHITECTURE.md §5）。
该事件不走 outbox（docs/SEARCH.md §1：非关键、允许少量丢失），失败只记 WARN。

Issue 没有事件：上文事件清单里没有 Issue 相关事件，因此 Issue 的索引由 Search 的
对账任务补齐（新建 / 状态变更后等下一次对账，或手动触发）。
需要实时性时补 `IssueCreatedEvent` / `IssueStatusChangedEvent` 并在此登记。

标签变更复用 `AssetUploadedEvent`（补齐开发，2026-09-24）：
`AssetUploadedEvent` 新增 `tags` 字段（标签名列表，旧消息缺失时消费端按空处理）；
事件体由 OutboxEventService 写入时统一回查当前标签，
生产方（发布 / autoPublish / 下架 / 标签整组替换）无需感知。
`TagService.setAssetTags` 在重建关联后与发布/下架走同一条
「opVersion 递增 + 同事务写 outbox」路径。
标签会进 ES 的 `tags` keyword 字段参与筛选，
不随事件同步的话搜索永远停在旧标签。
