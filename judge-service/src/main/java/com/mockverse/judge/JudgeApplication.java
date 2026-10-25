package com.mockverse.judge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * MockVerse MCP 判题服务（D18）。
 *
 * <p>职责单一：把"候选人提交的 Java 代码"真的编译并跑起来，把编译错误 / 超时 / 输出
 * 结构化地回给调用方。**不做"好不好"的评判**——那是面试官 Agent 的活，它手里有题干和
 * 参考答案；本服务只提供客观事实（能不能编译、跑出什么、有没有超时）。
 *
 * <p>暴露面：{@code POST /mcp}（MCP Streamable HTTP），工具 {@code judge_code}。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class JudgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(JudgeApplication.class, args);
    }
}
