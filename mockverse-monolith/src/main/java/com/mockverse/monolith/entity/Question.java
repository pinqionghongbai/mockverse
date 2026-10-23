package com.mockverse.monolith.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 题库实体。 */
@Data
@TableName("question")
public class Question {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** JAVA_BASIC / JVM / MYSQL / REDIS / SPRING / NETWORK / ALGORITHM */
    private String category;

    /** EASY / MEDIUM / HARD */
    private String difficulty;

    private String content;

    private String referenceAnswer;

    /** 逗号分隔标签 */
    private String tags;

    private Boolean enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
