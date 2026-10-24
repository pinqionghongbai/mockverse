package com.mockverse.interview.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 面试会话实体。
 *
 * <p>表名用 {@code interview_session} 而非 {@code session}: session 在 MySQL 里虽是非保留字,
 * 但作为通用词容易和 JDBC Session / HTTP Session 混淆, 且部分 ORM/中间件会误判。
 */
@Data
@TableName("interview_session")
public class InterviewSession {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private String title;

    /** IN_PROGRESS / COMPLETED / ABORTED */
    private String status;

    /** JAVA_BACKEND / ALGORITHM ... */
    private String interviewType;

    private Integer totalRounds;

    private Integer maxRounds;

    private LocalDateTime startedAt;

    private LocalDateTime endedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
