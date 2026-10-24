package com.mockverse.interview.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 聊天消息实体(增长最快的表)。 */
@Data
@TableName("chat_message")
public class ChatMessage {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long sessionId;

    /** 追问消息为空 */
    private Long questionId;

    /** USER / AGENT / SYSTEM */
    private String role;

    /** 出题 / 追问 / 诊断 —— 多 Agent 区分 */
    private String agentName;

    private String content;

    /** 会话内序号, 从 1 递增 */
    private Integer seqNo;

    /** 冗余字段: 写入时算一次, 避免每次展示历史都重算 */
    private Integer tokenCount;

    private LocalDateTime createdAt;
}
