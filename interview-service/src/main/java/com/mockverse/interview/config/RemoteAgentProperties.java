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

    /**
     * 单次跨进程调用的上限（秒）。
     *
     * <p><b>为什么必须有这个值</b>：a2a SDK 的 {@code JdkA2AHttpClient} 建请求时只设了 URI，
     * 既没有 {@code connectTimeout} 也没有 {@code HttpRequest.timeout()}——连接建立后对端一直
     * 不回，{@code send()} 会**永久**阻塞。而 {@code AgentRuntime.converse} 的整轮超时只能让
     * 调用方拿到降级话术，被卡住的订阅线程不会因此解开：远端挂一次就永久少一个线程（D17 六查）。
     *
     * <p><b>为什么是 60s</b>：服务端 {@code agentCompletionTimeoutSeconds} 默认 30s——超过这个
     * 时间它自己就会放回 {@code working} 快照（本类随后抛"未执行完成"）。60s 留了一倍余量，
     * 只在真正挂死（连快照都等不到）时才触发，正常慢请求（resume 实测 17.95s）不会被误杀。
     */
    private int timeoutSeconds = 60;
}
