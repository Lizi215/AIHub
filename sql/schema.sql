-- =====================================================================
-- AIHub 数据库初始化（完整 DDL，合并自 Phase 1~7 的增量脚本）
-- =====================================================================
-- 用法（在**全新库**上执行一次）：
--   mysql -h <host> -u <user> -p <database> < sql/schema.sql
--
-- 说明：本文件按依赖顺序合并了原先的增量脚本
--   （01_schema → 10_workspace_description），建表语句均为 IF NOT EXISTS。
-- 本文件不含 USE，目标库由连接参数指定。
-- 表结构与 Redis Key 说明见 docs/DATA.md；部署步骤见 docs/DEPLOYMENT.md。
-- 末尾附「首个管理员」的 UPDATE 模板（把 <username> 换成实际登录名后执行）。
-- =====================================================================

-- ============================================================
-- 来源：01_schema.sql
-- ============================================================

-- AIHub Phase 1 表结构
--
-- 兼容性说明：本机 MySQL 为 5.7.35（版本线原定 8.4，已获批准偏离）。
-- 因此统一使用 utf8mb4_general_ci（5.7 没有 8.0 才引入的 utf8mb4_0900_ai_ci），
-- 且不使用窗口函数、CHECK 约束、函数索引等 8.0+ 特性，保证 5.7 与 8.4 都能执行。
--
-- 通用约定（docs/DATA.md §2）：
--   * 主键统一雪花算法，由 MyBatis-Plus IdType.ASSIGN_ID 生成，DDL 不设 AUTO_INCREMENT
--   * deleted 为 BIGINT，0 表示未删除，删除时写入删除时间戳（毫秒）
--   * 业务唯一索引必须包含 deleted 列，否则删除后无法重建同名记录

CREATE DATABASE IF NOT EXISTS `aihub`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;


-- ---------------------------------------------------------------- 用户
CREATE TABLE IF NOT EXISTS `user`
(
    `id`          BIGINT       NOT NULL COMMENT '雪花 ID',
    `username`    VARCHAR(50)  NOT NULL COMMENT '登录名',
    `password`    VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希，禁止明文 / MD5 / SHA1',
    `nickname`    VARCHAR(50)           DEFAULT NULL COMMENT '昵称',
    `email`       VARCHAR(100)          DEFAULT NULL COMMENT '邮箱',
    `role`        VARCHAR(20)  NOT NULL DEFAULT 'USER' COMMENT 'USER / MAINTAINER / ADMIN，Visitor 不落库',
    `create_time` DATETIME     NOT NULL COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL COMMENT '更新时间',
    `deleted`     BIGINT       NOT NULL DEFAULT 0 COMMENT '0 未删除，否则为删除时间戳',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_username` (`username`, `deleted`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='用户';

-- ---------------------------------------------------------------- 资源
CREATE TABLE IF NOT EXISTS `asset`
(
    `id`             BIGINT       NOT NULL COMMENT '雪花 ID',
    `owner_id`       BIGINT       NOT NULL COMMENT '归属用户，权限判定依赖（docs/ARCHITECTURE.md §9.2）',
    `name`           VARCHAR(200) NOT NULL COMMENT '资源名',
    `summary`        VARCHAR(500)          DEFAULT NULL COMMENT '一句话简介',
    `category`       VARCHAR(50)  NOT NULL DEFAULT 'OTHER' COMMENT '资源分类',
    `status`         VARCHAR(20)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / PUBLISHED / SUSPENDED',
    `latest_version` VARCHAR(50)           DEFAULT NULL COMMENT '最新版本号',
    `create_time`    DATETIME     NOT NULL COMMENT '创建时间',
    `update_time`    DATETIME     NOT NULL COMMENT '更新时间',
    `deleted`        BIGINT       NOT NULL DEFAULT 0 COMMENT '0 未删除，否则为删除时间戳',
    PRIMARY KEY (`id`),
    KEY `idx_asset_owner` (`owner_id`),
    KEY `idx_asset_status_category` (`status`, `category`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='资源';

-- ---------------------------------------------------------------- 资源版本
CREATE TABLE IF NOT EXISTS `asset_version`
(
    `id`             BIGINT        NOT NULL COMMENT '雪花 ID',
    `asset_id`       BIGINT        NOT NULL COMMENT '所属资源',
    `version`        VARCHAR(50)   NOT NULL COMMENT '版本号，同一资源内唯一',
    `changelog`      VARCHAR(2000)          DEFAULT NULL COMMENT '更新日志',
    `readme`         MEDIUMTEXT             DEFAULT NULL COMMENT 'README 属描述性元数据，存 MySQL 而非对象存储',
    `publish_status` VARCHAR(20)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / PUBLISHED',
    `published_at`   DATETIME               DEFAULT NULL COMMENT '发布时间',
    `create_time`    DATETIME      NOT NULL COMMENT '创建时间',
    `update_time`    DATETIME      NOT NULL COMMENT '更新时间',
    `deleted`        BIGINT        NOT NULL DEFAULT 0 COMMENT '0 未删除，否则为删除时间戳',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_asset_version` (`asset_id`, `version`, `deleted`),
    KEY `idx_asset_version_asset` (`asset_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='资源版本';

-- ---------------------------------------------------------------- 文件元数据
CREATE TABLE IF NOT EXISTS `asset_file`
(
    `id`           BIGINT       NOT NULL COMMENT '雪花 ID',
    `asset_id`     BIGINT       NOT NULL COMMENT '所属资源',
    `version_id`   BIGINT       NOT NULL COMMENT '所属版本',
    `object_key`   VARCHAR(500) NOT NULL COMMENT '对象存储路径，由 Asset Service 决定，File Service 不解析语义',
    `size`         BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
    `content_type` VARCHAR(100)          DEFAULT NULL COMMENT '内容类型',
    `checksum`     VARCHAR(64)           DEFAULT NULL COMMENT 'SHA-256 十六进制摘要',
    `create_time`  DATETIME     NOT NULL COMMENT '创建时间',
    `update_time`  DATETIME     NOT NULL COMMENT '更新时间',
    `deleted`      BIGINT       NOT NULL DEFAULT 0 COMMENT '0 未删除，否则为删除时间戳',
    PRIMARY KEY (`id`),
    KEY `idx_asset_file_version` (`version_id`),
    KEY `idx_asset_file_asset` (`asset_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='资源文件元数据（只存元数据，绝不存文件内容）';


-- ============================================================
-- 来源：02_phase2.sql
-- ============================================================

-- AIHub Phase 2：资源版本与依赖
--
-- 兼容性同 01_schema.sql：utf8mb4_general_ci，不使用 8.0+ 专属特性。


-- ---------------------------------------------------------------- 资源依赖
CREATE TABLE IF NOT EXISTS `asset_dependency`
(
    `id`                  BIGINT       NOT NULL COMMENT '雪花 ID',
    `asset_id`            BIGINT       NOT NULL COMMENT '发起依赖的资源',
    `depends_on_asset_id` BIGINT       NOT NULL COMMENT '被依赖的资源',
    `version_constraint`  VARCHAR(50)           DEFAULT NULL COMMENT '版本约束，第一版只做建模不做解析',
    `create_time`         DATETIME     NOT NULL COMMENT '创建时间',
    `update_time`         DATETIME     NOT NULL COMMENT '更新时间',
    `deleted`             BIGINT       NOT NULL DEFAULT 0 COMMENT '0 未删除，否则为删除时间戳',
    PRIMARY KEY (`id`),
    -- 加入 deleted 后，删除依赖再重新添加不会撞唯一键（docs/DATA.md §2）
    UNIQUE KEY `uk_asset_dependency` (`asset_id`, `depends_on_asset_id`, `deleted`),
    KEY `idx_asset_dependency_asset` (`asset_id`),
    KEY `idx_asset_dependency_target` (`depends_on_asset_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='资源依赖关系';


-- ============================================================
-- 来源：03_phase3.sql
-- ============================================================

-- AIHub Phase 3：本地消息表（Outbox）
--
-- 为什么需要它：把「业务写库」与「发消息」放进同一个数据库事务，
-- 避免「库改了但消息丢了」或「消息发了但库回滚」。RocketMQ 由独立的中继任务投递。
-- 兼容性同前：utf8mb4_general_ci，不使用 8.0+ 专属特性。


-- asset.op_version：资源维度的操作版本，每次发布 +1。
-- 事件携带它，消费端据此丢弃乱序到达的旧事件（见 aihub-common 的 EventVersionPolicy）。
-- 注意：MySQL 5.7 不支持 ADD COLUMN IF NOT EXISTS，本语句重复执行会报错，
-- 属一次性迁移（Docker 首次初始化时由 docker-entrypoint 自动执行）。
ALTER TABLE `asset`
    ADD COLUMN `op_version` BIGINT NOT NULL DEFAULT 0 COMMENT '资源维度操作版本，每次发布递增，用于事件防乱序';

CREATE TABLE IF NOT EXISTS `event_outbox`
(
    `id`            BIGINT       NOT NULL COMMENT '雪花 ID',
    `event_id`      VARCHAR(64)  NOT NULL COMMENT '事件唯一标识，消费端据此幂等',
    `event_type`    VARCHAR(64)  NOT NULL COMMENT '事件类型，如 ASSET_UPLOADED',
    `event_version` INT          NOT NULL DEFAULT 1 COMMENT '事件结构版本，便于后续演进兼容',
    `op_version`    BIGINT       NOT NULL COMMENT '同一聚合维度的操作版本，用于消费端防乱序',
    `aggregate_id`  BIGINT       NOT NULL COMMENT '聚合根 id，这里的聚合是 asset',
    `topic`         VARCHAR(64)  NOT NULL COMMENT '目标 topic',
    `tag`           VARCHAR(64)           DEFAULT NULL COMMENT '目标 tag',
    `payload`       MEDIUMTEXT   NOT NULL COMMENT '事件体（JSON）',
    `status`        VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / SENT / FAILED',
    `retry_count`   INT          NOT NULL DEFAULT 0 COMMENT '投递重试次数',
    `next_retry_at` DATETIME              DEFAULT NULL COMMENT '下次可投递时间，退避用',
    `create_time`   DATETIME     NOT NULL COMMENT '创建时间',
    `update_time`   DATETIME     NOT NULL COMMENT '更新时间',
    `deleted`       BIGINT       NOT NULL DEFAULT 0 COMMENT '0 未删除，否则为删除时间戳',
    PRIMARY KEY (`id`),
    -- 事件唯一：中继重试不会产生重复事件
    UNIQUE KEY `uk_event_outbox_event_id` (`event_id`, `deleted`),
    -- 中继扫描待投递记录的驱动索引
    KEY `idx_event_outbox_status_retry` (`status`, `next_retry_at`),
    KEY `idx_event_outbox_aggregate` (`aggregate_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='本地消息表：业务事务内落库，另由中继投递到 MQ';

-- 消费端幂等记录（Redis 是首选，这里留一张兜底表以支持「Redis 不可用后重建」）
CREATE TABLE IF NOT EXISTS `event_consumed`
(
    `id`           BIGINT      NOT NULL COMMENT '雪花 ID',
    `event_id`     VARCHAR(64) NOT NULL COMMENT '已消费事件 id',
    `consumer`     VARCHAR(64) NOT NULL COMMENT '消费者标识，同一事件可被不同消费者各消费一次',
    `consumed_at`  DATETIME    NOT NULL COMMENT '消费时间',
    `create_time`  DATETIME    NOT NULL COMMENT '创建时间',
    `update_time`  DATETIME    NOT NULL COMMENT '更新时间',
    `deleted`      BIGINT      NOT NULL DEFAULT 0 COMMENT '0 未删除，否则为删除时间戳',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_event_consumed` (`event_id`, `consumer`, `deleted`),
    KEY `idx_event_consumed_time` (`consumed_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='消费幂等兜底记录';


-- ============================================================
-- 来源：04_phase4.sql
-- ============================================================

-- AIHub Phase 4：下载记录与统计
--
-- 归属：download_record 归 User Service（用户维度的下载历史），
-- 下载计数与热门榜归 Asset Service（存 Redis，不是这张表）。


CREATE TABLE IF NOT EXISTS `download_record`
(
    `id`            BIGINT      NOT NULL COMMENT '雪花 ID',
    `user_id`       BIGINT      NOT NULL COMMENT '下载者',
    `asset_id`      BIGINT      NOT NULL COMMENT '资源',
    `version`       VARCHAR(50)          DEFAULT NULL COMMENT '版本号',
    `occurred_at`   DATETIME    NOT NULL COMMENT '下载发生时间（取自事件，不用落库时间）',
    `create_time`   DATETIME    NOT NULL COMMENT '创建时间',
    `update_time`   DATETIME    NOT NULL COMMENT '更新时间',
    `deleted`       BIGINT      NOT NULL DEFAULT 0 COMMENT '0 未删除，否则为删除时间戳',
    PRIMARY KEY (`id`),
    -- 下载记录没有业务唯一键（同一用户可重复下载），因此不设唯一索引；
    -- 事件级幂等由 eventId 在消费端保证
    KEY `idx_download_record_user` (`user_id`, `occurred_at`),
    KEY `idx_download_record_asset` (`asset_id`, `occurred_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='下载记录（用户维度）';


-- ============================================================
-- 来源：05_phase5.sql
-- ============================================================

-- AIHub Phase 5：社区
--
-- 帖子类内容用 post + post.type 区分 Experience / Guide / Question（不是独立表）；
-- Issue 是独立实体（需关联 Asset 与 Asset Version，字段差异大）。


-- ---------------------------------------------------------------- 帖子
CREATE TABLE IF NOT EXISTS `post`
(
    `id`          BIGINT       NOT NULL COMMENT '雪花 ID',
    `author_id`   BIGINT       NOT NULL COMMENT '作者',
    `type`        VARCHAR(20)  NOT NULL COMMENT 'EXPERIENCE / GUIDE / QUESTION',
    `title`       VARCHAR(200) NOT NULL COMMENT '标题',
    `content`     MEDIUMTEXT   NOT NULL COMMENT '正文，按纯文本 / Markdown 存储，禁止存 HTML',
    `asset_id`    BIGINT                DEFAULT NULL COMMENT '关联资源，可空',
    `like_count`  BIGINT       NOT NULL DEFAULT 0 COMMENT '点赞数，MySQL 为唯一事实来源',
    `create_time` DATETIME     NOT NULL COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL COMMENT '更新时间',
    `deleted`     BIGINT       NOT NULL DEFAULT 0 COMMENT '0 未删除，否则为删除时间戳',
    PRIMARY KEY (`id`),
    KEY `idx_post_author` (`author_id`),
    KEY `idx_post_type_create` (`type`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='社区帖子';

-- ---------------------------------------------------------------- 帖子点赞
CREATE TABLE IF NOT EXISTS `post_like`
(
    `id`          BIGINT   NOT NULL COMMENT '雪花 ID',
    `post_id`     BIGINT   NOT NULL COMMENT '帖子',
    `user_id`     BIGINT   NOT NULL COMMENT '点赞者',
    `create_time` DATETIME NOT NULL COMMENT '创建时间',
    `update_time` DATETIME NOT NULL COMMENT '更新时间',
    `deleted`     BIGINT   NOT NULL DEFAULT 0 COMMENT '0 未删除，否则为删除时间戳',
    PRIMARY KEY (`id`),
    -- 「重复点赞不重复计」的**硬保证**：Redis Set 只是快路径与缓存，
    -- 即使 Redis 数据丢失，这里也会拦住重复插入（docs/DATA.md §2 唯一索引含 deleted）
    UNIQUE KEY `uk_post_like` (`post_id`, `user_id`, `deleted`),
    KEY `idx_post_like_user` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='帖子点赞';

-- ---------------------------------------------------------------- Issue
CREATE TABLE IF NOT EXISTS `issue`
(
    `id`          BIGINT       NOT NULL COMMENT '雪花 ID',
    `author_id`   BIGINT       NOT NULL COMMENT '提出者',
    `asset_id`    BIGINT       NOT NULL COMMENT '关联资源',
    `version`     VARCHAR(50)           DEFAULT NULL COMMENT '关联版本',
    `title`       VARCHAR(200) NOT NULL COMMENT '标题',
    `content`     MEDIUMTEXT   NOT NULL COMMENT '正文，纯文本 / Markdown',
    `status`      VARCHAR(20)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN / PROCESSING / CLOSED',
    `create_time` DATETIME     NOT NULL COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL COMMENT '更新时间',
    `deleted`     BIGINT       NOT NULL DEFAULT 0 COMMENT '0 未删除，否则为删除时间戳',
    PRIMARY KEY (`id`),
    KEY `idx_issue_asset` (`asset_id`, `status`),
    KEY `idx_issue_author` (`author_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='Issue（独立实体，有独立状态机）';

-- ---------------------------------------------------------------- Issue 评论
CREATE TABLE IF NOT EXISTS `issue_comment`
(
    `id`          BIGINT     NOT NULL COMMENT '雪花 ID',
    `issue_id`    BIGINT     NOT NULL COMMENT 'Issue',
    `author_id`   BIGINT     NOT NULL COMMENT '评论者',
    `content`     MEDIUMTEXT NOT NULL COMMENT '正文，纯文本 / Markdown',
    `create_time` DATETIME   NOT NULL COMMENT '创建时间',
    `update_time` DATETIME   NOT NULL COMMENT '更新时间',
    `deleted`     BIGINT     NOT NULL DEFAULT 0 COMMENT '0 未删除，否则为删除时间戳',
    PRIMARY KEY (`id`),
    KEY `idx_issue_comment_issue` (`issue_id`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='Issue 评论';


-- ============================================================
-- 来源：06_phase5b.sql
-- ============================================================

-- AIHub Phase 5 补充：评论与收藏
--
-- 05_phase5.sql 已建 post / post_like / issue / issue_comment；
-- 这里补 post_comment 与 post_favorite。


CREATE TABLE IF NOT EXISTS `post_comment`
(
    `id`          BIGINT     NOT NULL COMMENT '雪花 ID',
    `post_id`     BIGINT     NOT NULL COMMENT '帖子',
    `author_id`   BIGINT     NOT NULL COMMENT '评论者',
    `content`     MEDIUMTEXT NOT NULL COMMENT '正文，纯文本 / Markdown，禁止存 HTML',
    `create_time` DATETIME   NOT NULL COMMENT '创建时间',
    `update_time` DATETIME   NOT NULL COMMENT '更新时间',
    `deleted`     BIGINT     NOT NULL DEFAULT 0 COMMENT '0 未删除，否则为删除时间戳',
    PRIMARY KEY (`id`),
    KEY `idx_post_comment_post` (`post_id`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='帖子评论';

CREATE TABLE IF NOT EXISTS `post_favorite`
(
    `id`          BIGINT   NOT NULL COMMENT '雪花 ID',
    `post_id`     BIGINT   NOT NULL COMMENT '帖子',
    `user_id`     BIGINT   NOT NULL COMMENT '收藏者',
    `create_time` DATETIME NOT NULL COMMENT '创建时间',
    `update_time` DATETIME NOT NULL COMMENT '更新时间',
    `deleted`     BIGINT   NOT NULL DEFAULT 0 COMMENT '0 未删除，否则为删除时间戳',
    PRIMARY KEY (`id`),
    -- 唯一索引含 deleted：否则「取消收藏后重新收藏」会撞唯一键而永远收藏不上
    UNIQUE KEY `uk_post_favorite` (`post_id`, `user_id`, `deleted`),
    KEY `idx_post_favorite_user` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='帖子收藏';


-- ============================================================
-- 来源：07_phase6.sql
-- ============================================================

-- AIHub Phase 6 表结构：Agent 异步任务
--
-- 兼容性说明：本机 MySQL 为 5.7.35（版本线原定 8.4，已获批准偏离），
-- 因此统一使用 utf8mb4_general_ci，不使用窗口函数、CHECK 约束、函数索引等 8.0+ 特性。
--
-- 数据归属（docs/ARCHITECTURE.md §6）：`agent_task` 归 AI Service。
-- AI Service 只持有这张表，**不持有任何业务数据**；Agent 取数一律走
-- Agent → Tool → 业务服务 → 基础设施（架构约定）。
--
-- 为什么任务必须落库而不是放内存：
--   1. Agent 任务耗时不可控（多轮 Tool Calling 可能数十秒），必须异步执行，
--      进程重启后任务不能凭空消失；
--   2. docs/SECURITY.md §4 要求「每次执行的步骤、选用的 Tool、参数与结果必须落
--      agent_task，否则出错无法复现」——这是可复现性的硬要求，不是可选日志。


CREATE TABLE IF NOT EXISTS `agent_task`
(
    `id`                 BIGINT      NOT NULL COMMENT '雪花 ID',
    `user_id`            BIGINT      NOT NULL COMMENT '发起者',
    `scene`              VARCHAR(30) NOT NULL COMMENT '场景：SEARCH / DOWNLOAD / WORKSPACE / UPLOAD / BUG_ANALYSIS',
    `request`            TEXT        NOT NULL COMMENT '用户请求原文（纯文本，禁止 HTML）',
    `status`             VARCHAR(20) NOT NULL COMMENT 'PENDING / RUNNING / WAITING_CONFIRM / SUCCEEDED / FAILED / CANCELLED',
    `plan`               MEDIUMTEXT           DEFAULT NULL COMMENT '计划 JSON：步骤列表 + 需确认标记',
    `steps`              MEDIUMTEXT           DEFAULT NULL COMMENT '已执行步骤 JSON：Tool 名 / 参数 / 结果摘要 / 状态 / 失败原因',
    `result`             MEDIUMTEXT           DEFAULT NULL COMMENT '汇总结果 JSON（区分已完成 / 失败 / 失败原因）',
    `error`              VARCHAR(500)         DEFAULT NULL COMMENT '终态为 FAILED 时的失败原因',
    `step_count`         INT         NOT NULL DEFAULT 0 COMMENT '已执行步数，用于步数上限',
    `token_used`         INT         NOT NULL DEFAULT 0 COMMENT '累计 Token 估算，用于成本限额',
    `injection_detected` TINYINT     NOT NULL DEFAULT 0 COMMENT '上下文中的不可信内容是否命中 Prompt 注入特征，命中则为 1',
    `model`              VARCHAR(50)          DEFAULT NULL COMMENT '执行模式：mock 或实际模型名（用于区分无 Key 演示与真实调用）',
    `confirmed_time`     DATETIME             DEFAULT NULL COMMENT '用户提交确认的时间，未确认为 NULL',
    `create_time`        DATETIME    NOT NULL COMMENT '创建时间',
    `update_time`        DATETIME    NOT NULL COMMENT '更新时间',
    `deleted`            BIGINT      NOT NULL DEFAULT 0 COMMENT '0 未删除，否则为删除时间戳',
    PRIMARY KEY (`id`),
    KEY `idx_agent_task_user` (`user_id`, `create_time`),
    KEY `idx_agent_task_status` (`status`, `update_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='Agent 异步任务';


-- ============================================================
-- 来源：08_agent_task_session.sql
-- ============================================================

-- agent_task 增加所属会话标识（2026-09-28 多轮会话功能）。
-- 会话历史本体在 Redis（agent:chat:{userId}:{sessionId}，List + TTL 2h，不持久化），
-- 这里只落 sessionId 字符串：Orchestrator 异步执行时只拿得到 taskId，
-- 确认后续跑（runAfterConfirm）与终态回写历史都需要从任务行取回 sessionId。
-- 可空：不带会话的旧请求行为不变。

ALTER TABLE `agent_task`
    ADD COLUMN `session_id` VARCHAR(64) NULL DEFAULT NULL COMMENT '所属会话（前端生成的 UUID，可空）' AFTER `user_id`,
    ADD KEY `idx_agent_task_session` (`session_id`, `create_time`);


-- ============================================================
-- 来源：08_phase7.sql
-- ============================================================

-- AIHub Phase 7：加固与交付
--
-- 权限模型（docs/ARCHITECTURE.md §9.3）：首个 Admin 由本脚本写入。
-- 用法：把 <username> 替换为要提升的登录名后执行对应语句。
-- 注意：角色随 JWT 签发，变更后该用户需重新登录才生效。


-- 将指定用户提升为 ADMIN（取消注释并替换用户名后执行）：
-- UPDATE `user` SET `role` = 'ADMIN' WHERE `username` = '<username>' AND `deleted` = 0;

-- 将指定用户提升为 MAINTAINER（社区版主）：
-- UPDATE `user` SET `role` = 'MAINTAINER' WHERE `username` = '<username>' AND `deleted` = 0;


-- ============================================================
-- 来源：09_workspace_tags.sql
-- ============================================================

-- AIHub 补充：Workspace 与标签（docs/ARCHITECTURE.md §2 / §3，docs/DATA.md §1）
--
-- 数据归属：workspace / workspace_item 归 User Service；tag / asset_tag 归 Asset Service。
-- 软删除与唯一键约定同 docs/DATA.md §2：唯一索引必须包含 deleted。


-- ------------------------------------------------ Workspace：用户的资源组合方案（AI 出草案，用户确认后落库）

CREATE TABLE IF NOT EXISTS `workspace`
(
    `id`          BIGINT       NOT NULL COMMENT '雪花 ID',
    `user_id`     BIGINT       NOT NULL COMMENT '归属用户',
    `name`        VARCHAR(100) NOT NULL COMMENT '名称',
    `create_time` DATETIME     NOT NULL COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL COMMENT '更新时间',
    `deleted`     BIGINT       NOT NULL DEFAULT 0 COMMENT '0 未删除，否则为删除时间戳',
    PRIMARY KEY (`id`),
    KEY `idx_workspace_user` (`user_id`, `deleted`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='Workspace：用户的资源组合方案';

CREATE TABLE IF NOT EXISTS `workspace_item`
(
    `id`           BIGINT      NOT NULL COMMENT '雪花 ID',
    `workspace_id` BIGINT      NOT NULL COMMENT '所属 Workspace',
    `asset_id`     BIGINT      NOT NULL COMMENT '资源 id（名字以 MySQL 为准，展示时经 Feign 回查）',
    `version`      VARCHAR(50)          DEFAULT NULL COMMENT '选定版本，NULL 表示最新版本',
    `create_time`  DATETIME    NOT NULL COMMENT '创建时间',
    `update_time`  DATETIME    NOT NULL COMMENT '更新时间',
    `deleted`      BIGINT      NOT NULL DEFAULT 0 COMMENT '0 未删除，否则为删除时间戳',
    PRIMARY KEY (`id`),
    -- 同一 Workspace 内同一资源只出现一次（换版本走更新而不是新增）
    UNIQUE KEY `uk_workspace_item` (`workspace_id`, `asset_id`, `deleted`),
    KEY `idx_workspace_item_ws` (`workspace_id`, `deleted`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='Workspace 条目';

-- ------------------------------------------------ 标签（docs/ARCHITECTURE.md §3：tag 字典 + asset_tag 关联，Tag 只服务 Asset）

CREATE TABLE IF NOT EXISTS `tag`
(
    `id`          BIGINT       NOT NULL COMMENT '雪花 ID',
    `name`        VARCHAR(50)  NOT NULL COMMENT '标签名，唯一',
    `create_time` DATETIME     NOT NULL COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL COMMENT '更新时间',
    `deleted`     BIGINT       NOT NULL DEFAULT 0 COMMENT '0 未删除，否则为删除时间戳',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tag_name` (`name`, `deleted`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='标签字典';

CREATE TABLE IF NOT EXISTS `asset_tag`
(
    `id`          BIGINT   NOT NULL COMMENT '雪花 ID',
    `asset_id`    BIGINT   NOT NULL COMMENT '资源 id',
    `tag_id`      BIGINT   NOT NULL COMMENT '标签 id（字典外键，逻辑关联不落物理外键）',
    `create_time` DATETIME NOT NULL COMMENT '创建时间',
    `update_time` DATETIME NOT NULL COMMENT '更新时间',
    `deleted`     BIGINT   NOT NULL DEFAULT 0 COMMENT '0 未删除，否则为删除时间戳',
    PRIMARY KEY (`id`),
    -- 同一资源同一标签只挂一次
    UNIQUE KEY `uk_asset_tag` (`asset_id`, `tag_id`, `deleted`),
    KEY `idx_asset_tag_tag` (`tag_id`, `deleted`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='资源-标签关联';


-- ============================================================
-- 来源：10_workspace_description.sql
-- ============================================================

-- Workspace 增加简介列（2026-09-28）。
-- 简介由 AI 在生成 Workspace 草案时写出（build_workspace_draft 的 description 参数），
-- 经用户确认保存后落库；旧数据为 NULL，前端显示占位符。

ALTER TABLE `workspace`
    ADD COLUMN `description` VARCHAR(500) NULL DEFAULT NULL COMMENT '简介（AI 草案生成，可空）' AFTER `name`;


