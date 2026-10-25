package com.mockverse.interview.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 判题 MCP Server 的连接配置（{@code mockverse.judge.*}，D18）。
 *
 * <p><b>为什么把 enabled 做成开关</b>：D17 立下的规矩是"远端不可用时面试流程必须照常走"。
 * 判题服务是第四个进程，演示机上不一定起；关掉开关，面试官就只是没有 judge_code 这个工具，
 * 其余能力不受影响。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mockverse.judge")
public class JudgeMcpProperties {

    /** 是否把 judge_code 挂到面试官 Agent 上。 */
    private boolean enabled = true;

    /** judge-service 的 MCP 端点（Streamable HTTP）。 */
    private String url = "http://localhost:8084/mcp";

    /**
     * 初始化握手超时。
     *
     * <p>ADK 的 {@code McpSessionManager} 会把它当 {@code initializationTimeout}；
     * 不显式给的话默认 5 分钟——judge-service 没起来时每一轮都要挂 5 分钟，那还不如不接。
     */
    private Duration initTimeout = Duration.ofSeconds(10);

    /**
     * 单次工具调用超时（{@code requestTimeout}）。
     *
     * <p>必须大于判题服务自己的运行上限（默认 10s）+ 编译（20s）+ 排队，否则会把
     * "服务端还在判题"误报成"工具调用失败"。
     */
    private Duration requestTimeout = Duration.ofSeconds(45);

    /** 工具列表加载失败后的冷却时间：这段时间内不再重试，直接当作没有判题工具。 */
    private Duration failureCooldown = Duration.ofSeconds(30);
}
