package com.mockverse.monolith;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * MockVerse 单体 v1 启动类。
 *
 * <p>D8 目标: 验证 Spring Boot 本体 + MyBatis-Plus + MySQL 三者真的能跑起来
 * (第一周欠下的第 7 项 JD 的还债日)。
 */
@SpringBootApplication
@MapperScan("com.mockverse.monolith.mapper")
public class MockVerseApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockVerseApplication.class, args);
    }
}
