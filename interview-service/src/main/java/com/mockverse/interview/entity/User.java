package com.mockverse.interview.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户表实体。
 *
 * <p>{@code user} 是 MySQL 非保留关键字(同时是内置函数名), 加反引号最保险。
 */
@Data
@TableName("`user`")
public class User {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String username;

    /** BCrypt 哈希, 永不存明文 */
    private String passwordHash;

    private String email;

    private String nickname;

    /** 由 DB 默认值填充, 插入时不显式赋值 */
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
