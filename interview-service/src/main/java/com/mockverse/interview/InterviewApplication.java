package com.mockverse.interview;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 面试官服务入口（D15 自单体机械拆分而来）。
 *
 * <p>单体时代的 MockVerseApplication 保留在 mockverse-monolith (tag v0.1-monolith 基线)。
 */
@SpringBootApplication
@MapperScan("com.mockverse.interview.mapper")
public class InterviewApplication {

    public static void main(String[] args) {
        SpringApplication.run(InterviewApplication.class, args);
    }
}
