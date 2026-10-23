-- ============================================================
-- MockVerse 初始化脚本 v0.2 (D8 落地版 + D11 增补)
-- 相对 docs/db-design.md D7 初稿的变更:
--   D8: session -> interview_session (避免与 JDBC/HTTP Session 混淆)
--   D11: 新增 resume_diagnosis (简历诊断结果, 明细存 JSON 列)
-- 约定: 主键 BIGINT 雪花 ID, 应用层生成 (MyBatis-Plus ASSIGN_ID)
-- ============================================================
CREATE DATABASE IF NOT EXISTS mockverse
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE mockverse;

CREATE TABLE IF NOT EXISTS `user` (
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

CREATE TABLE IF NOT EXISTS `interview_session` (
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

CREATE TABLE IF NOT EXISTS `question` (
  `id`               BIGINT        NOT NULL COMMENT '主键(雪花ID)',
  `category`         VARCHAR(32)   NOT NULL COMMENT 'JAVA_BASIC/JVM/MYSQL/REDIS/SPRING/NETWORK/ALGORITHM',
  `difficulty`       VARCHAR(16)   NOT NULL DEFAULT 'MEDIUM' COMMENT 'EASY/MEDIUM/HARD',
  `content`          VARCHAR(1000) NOT NULL COMMENT '题干',
  `reference_answer` TEXT                   COMMENT '参考答案',
  `tags`             VARCHAR(255)           DEFAULT NULL COMMENT '逗号分隔标签',
  `enabled`          TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '是否启用',
  `created_at`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_cat_diff_enabled` (`category`, `difficulty`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='题库表';

CREATE TABLE IF NOT EXISTS `chat_message` (
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

-- D11: 简历诊断结果
-- 设计要点: 明细维度与问题清单存 JSON 列, 不再拆成两张子表。
-- 理由: 这两类数据只随诊断单整体读取/展示, 从不按维度或问题单独查询,
-- 拆表只会增加 join 成本与维护负担; 需要统计时 MySQL 8 的 JSON 函数够用。
CREATE TABLE IF NOT EXISTS `resume_diagnosis` (
  `id`             BIGINT       NOT NULL COMMENT '主键(雪花ID)',
  `user_id`        BIGINT       NOT NULL COMMENT '所属用户',
  `resume_text`    MEDIUMTEXT   NOT NULL COMMENT '原始简历文本',
  `overall_score`  INT          NOT NULL COMMENT '总分 0-100',
  `dimensions`     JSON         NOT NULL COMMENT '各维度评分与点评 [{name,score,strengths,weaknesses}]',
  `issues`         JSON         NOT NULL COMMENT '问题清单 [{severity,quote,problem,suggestion}]',
  `summary`        VARCHAR(1000)         DEFAULT NULL COMMENT '一句话总评',
  `model`          VARCHAR(64)  NOT NULL COMMENT '生成该结果的模型名(便于换模型后对比)',
  `elapsed_ms`     INT          NOT NULL DEFAULT 0 COMMENT '模型耗时',
  `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at` DESC),
  KEY `idx_score` (`overall_score`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='简历诊断结果表';
