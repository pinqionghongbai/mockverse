# MockVerse 数据库设计（D7 初稿）

> 四张核心表：`user` / `interview_session` / `question` / `chat_message`。
> 设计目标：支撑"一个用户发起多场模拟面试，每场面试有多轮问答，题目来自题库"这条主链路。
> 这份设计同时是**索引优化八股**的素材来源——每个索引都写清"为什么建"。
>
> **D8 落地变更**：会话表由 `session` 改名为 `interview_session`。理由：`session` 虽是 MySQL 非保留字，
> 但作为通用词与 JDBC Session / HTTP Session 撞名，阅读和排错时容易误判；改名成本在 D8 最低。
> `user` 保留原名（业界惯例），仅在 DDL 与 `@TableName` 中加反引号。

---

## 一、E-R 图

```mermaid
erDiagram
    USER ||--o{ INTERVIEW_SESSION : "发起"
    INTERVIEW_SESSION ||--o{ CHAT_MESSAGE : "包含"
    QUESTION ||--o{ CHAT_MESSAGE : "被引用"
    USER {
        bigint id PK "雪花ID"
        varchar username UK "登录名"
        varchar password_hash "BCrypt"
        varchar email "邮箱"
        varchar nickname "昵称"
        datetime created_at
        datetime updated_at
    }

    INTERVIEW_SESSION {
        bigint id PK "雪花ID"
        bigint user_id FK "所属用户"
        varchar title "会话标题"
        varchar status "IN_PROGRESS/COMPLETED/ABORTED"
        varchar interview_type "JAVA_BACKEND/ALGORITHM..."
        int total_rounds "已完成轮数"
        int max_rounds "最大轮数"
        datetime started_at
        datetime ended_at
        datetime created_at
        datetime updated_at
    }

    QUESTION {
        bigint id PK "雪花ID"
        varchar category "JAVA_BASIC/JVM/MYSQL/REDIS/SPRING/NETWORK/ALGORITHM"
        varchar difficulty "EASY/MEDIUM/HARD"
        varchar content "题干"
        text reference_answer "参考答案"
        varchar tags "逗号分隔标签"
        boolean enabled "是否启用"
        datetime created_at
        datetime updated_at
    }

    CHAT_MESSAGE {
        bigint id PK "雪花ID"
        bigint session_id FK "所属会话"
        bigint question_id FK "关联题目(可空)"
        varchar role "USER/AGENT/SYSTEM"
        varchar agent_name "出题/追问/诊断"
        mediumtext content "消息内容"
        int seq_no "会话内序号"
        int token_count "token数(统计用)"
        datetime created_at
    }
```

---

## 二、建表 SQL（MySQL 8.0，InnoDB，utf8mb4）

```sql
-- ============================================================
-- MockVerse 初始化脚本 v0.1（D7 初稿）
-- 约定：主键用 BIGINT（雪花ID，应用层生成），避免自增在分库时冲突
-- ============================================================
CREATE DATABASE IF NOT EXISTS mockverse
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE mockverse;

-- ------------------------------------------------------------
-- 1. 用户表
-- ------------------------------------------------------------
CREATE TABLE `user` (
  `id`            BIGINT       NOT NULL COMMENT '主键(雪花ID)',
  `username`      VARCHAR(64)  NOT NULL COMMENT '登录名',
  `password_hash` VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希,不存明文',
  `email`         VARCHAR(128)          DEFAULT NULL COMMENT '邮箱',
  `nickname`      VARCHAR(64)           DEFAULT NULL COMMENT '昵称',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  KEY `idx_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- ------------------------------------------------------------
-- 2. 面试会话表（D8 由 session 改名，避免与 JDBC/HTTP Session 混淆）
-- ------------------------------------------------------------
CREATE TABLE `interview_session` (
  `id`             BIGINT      NOT NULL COMMENT '主键(雪花ID)',
  `user_id`        BIGINT      NOT NULL COMMENT '所属用户',
  `title`          VARCHAR(128)         DEFAULT NULL COMMENT '会话标题',
  `status`         VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS'
                   COMMENT 'IN_PROGRESS/COMPLETED/ABORTED',
  `interview_type` VARCHAR(32) NOT NULL DEFAULT 'JAVA_BACKEND' COMMENT '面试类型',
  `total_rounds`   INT         NOT NULL DEFAULT 0 COMMENT '已完成轮数',
  `max_rounds`     INT         NOT NULL DEFAULT 5 COMMENT '最大轮数(可被 Nacos 动态调整)',
  `started_at`     DATETIME             DEFAULT NULL,
  `ended_at`       DATETIME             DEFAULT NULL,
  `created_at`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at` DESC),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='面试会话表';

-- ------------------------------------------------------------
-- 3. 题库表
-- ------------------------------------------------------------
CREATE TABLE `question` (
  `id`               BIGINT       NOT NULL COMMENT '主键(雪花ID)',
  `category`         VARCHAR(32)  NOT NULL COMMENT 'JAVA_BASIC/JVM/MYSQL/REDIS/SPRING/NETWORK/ALGORITHM',
  `difficulty`       VARCHAR(16)  NOT NULL DEFAULT 'MEDIUM' COMMENT 'EASY/MEDIUM/HARD',
  `content`          VARCHAR(1000) NOT NULL COMMENT '题干',
  `reference_answer` TEXT                  COMMENT '参考答案',
  `tags`             VARCHAR(255)          DEFAULT NULL COMMENT '逗号分隔标签',
  `enabled`          TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
  `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_cat_diff_enabled` (`category`, `difficulty`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='题库表';

-- ------------------------------------------------------------
-- 4. 聊天消息表（最大的表，重点设计）
-- ------------------------------------------------------------
CREATE TABLE `chat_message` (
  `id`          BIGINT      NOT NULL COMMENT '主键(雪花ID)',
  `session_id`  BIGINT      NOT NULL COMMENT '所属会话',
  `question_id` BIGINT               DEFAULT NULL COMMENT '关联题目(追问消息为空)',
  `role`        VARCHAR(16) NOT NULL COMMENT 'USER/AGENT/SYSTEM',
  `agent_name`  VARCHAR(64)          DEFAULT NULL COMMENT '出题/追问/诊断(多 Agent 区分)',
  `content`     MEDIUMTEXT  NOT NULL COMMENT '消息内容',
  `seq_no`      INT         NOT NULL COMMENT '会话内序号,从1递增',
  `token_count` INT         NOT NULL DEFAULT 0 COMMENT 'token 数(统计/成本核算)',
  `created_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_session_seq` (`session_id`, `seq_no`),
  KEY `idx_session_created` (`session_id`, `created_at`),
  KEY `idx_question` (`question_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='聊天消息表';
```

---

## 三、索引设计说明（面试重点）

| 索引 | 表 | 为什么建 |
|---|---|---|
| `uk_username` | user | 登录必须唯一；且登录查询走这个唯一索引，天然覆盖 |
| `idx_user_created` | interview_session | 「我的面试历史」按用户查、按时间倒序翻页 → **联合索引避免回表排序**（最左前缀 `user_id` 定位，第二列 `created_at` 直接有序，省掉 filesort） |
| `idx_cat_diff_enabled` | question | 出题时「随机抽一道 MEDIUM 的 MYSQL 题」→ 三列联合，`enabled` 放最后因为区分度最低 |
| `uk_session_seq` | chat_message | ① 保证会话内消息序号唯一，防并发重复插入 ② 查「某会话全部消息按序」直接走索引有序输出 |
| `idx_session_created` | chat_message | 冗余但必要：`seq_no` 可能因重试跳号，按时间排序的兜底路径 |

**关键取舍：为什么主键用 BIGINT 雪花 ID 而不是自增？**
- 自增 ID 在分库分表时冲突；且会**泄露业务量**（竞品能算出你一天多少单）
- 雪花 ID 趋势递增（时间有序），InnoDB 聚簇索引插入仍是顺序追加，**不会像 UUID 那样随机写导致页分裂**
- 代价：需要处理时钟回拨（本项目单机，用 `MyBatis-Plus` 的 `ASSIGN_ID` 即可）

**为什么不给 `content` 建全文索引？**
- 本项目检索需求是"按会话拉全部消息"，不是全文搜索；MEDIUMTEXT 建全文索引会让写入变慢且索引体积巨大
- 若将来要"搜索历史提问"，应接 Elasticsearch，而不是在 MySQL 上硬堆

---

## 四、四表如何支撑主链路

```
用户登录        → user (uk_username 查)
发起面试        → interview_session INSERT (status=IN_PROGRESS)
出题            → question (idx_cat_diff_enabled 随机抽)
用户作答        → chat_message INSERT (role=USER, seq_no=1)
Agent 追问      → chat_message INSERT (role=AGENT, seq_no=2, agent_name='追问')
会话恢复        → chat_message WHERE session_id=? ORDER BY seq_no（走 uk_session_seq）
结束面试        → interview_session UPDATE (status=COMPLETED, ended_at=NOW())
```

---

## 五、已知局限（面试主动讲，反而加分）

1. **没有软删除**：`question.enabled` 只是逻辑停用，`user` 表删用户会留下孤儿 interview_session。生产应加 `deleted_at` + 逻辑删除。
2. **没有分表**：`chat_message` 是增长最快的表（一场面试 10+ 条消息 × 每天千场）。预估日增 1 万条、年增 365 万，单表还能撑；**超过 1000 万行再按 `session_id` 哈希分表**——现在分表属于过度设计（YAGNI）。
3. **token_count 是冗余字段**：正常化应该实时算，但每次展示历史都要重算 token 太浪费，所以写入时算一次存下来——**这是有意的反范式**。
4. **没有审计字段**：缺 `created_by` / `updated_by`，多租户场景需要补。
