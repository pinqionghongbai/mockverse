package com.mockverse.interview.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 跨进程子 Agent 的远端地址（D17）。
 *
 * <p><b>默认值指向本地端口而不是写死在代码里</b>：三个服务在本机联调时端口固定
 * （8081/8082/8083，见 STATUS 6.4），但换机器/换容器时只改 yml 或环境变量即可。
 *
 * <p><b>启动期就会拉卡片</b>：interview-service 起来时必须能连上这两个地址，
 * 否则 {@code MultiAgentFactory} 解析卡片失败、整个服务起不来（D16 建议 3）。
 * 这是有意的 fail-fast——半启动的服务只会把"路由到一个不存在的 Agent"变成更难查的运行时错误。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mockverse.remote")
public class RemoteAgentProperties {

    /** knowledge-service 的基地址（AgentCard 在 {@code /.well-known/agent-card.json}）。 */
    private String knowledgeUrl = "http://localhost:8082";

    /** resume-service 的基地址。 */
    private String resumeUrl = "http://localhost:8083";
}
