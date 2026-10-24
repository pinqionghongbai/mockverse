package com.mockverse.resume.a2a;

import com.google.adk.a2a.executor.AgentExecutor;
import com.google.adk.a2a.executor.AgentExecutorConfig;
import com.google.adk.agents.BaseAgent;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.sessions.InMemorySessionService;
import com.mockverse.resume.agent.ResumeAgentFactory;
import com.mockverse.resume.agent.ResumeAgentPrompts;
import io.a2a.server.events.InMemoryQueueManager;
import io.a2a.server.requesthandlers.DefaultRequestHandler;
import io.a2a.server.tasks.BasePushNotificationSender;
import io.a2a.server.tasks.InMemoryPushNotificationConfigStore;
import io.a2a.server.tasks.InMemoryTaskStore;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentSkill;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * A2A 服务端装配（D16）。
 *
 * <p><b>与 interview-service 的同名文件是同一套装配</b>，只有 Agent 树与 AgentCard 不同。
 * 三份拷贝是有意的（服务独立可构建、不引入额外模块），代价是改一处必须同步三处：
 * <ul>
 *   <li>interview-service/src/main/java/com/mockverse/interview/a2a/A2AServerConfig.java</li>
 *   <li>knowledge-service/src/main/java/com/mockverse/knowledge/a2a/A2AServerConfig.java</li>
 *   <li>resume-service/src/main/java/com/mockverse/resume/a2a/A2AServerConfig.java</li>
 * </ul>
 *
 * <p><b>为什么不是"引入 adk-java 的 a2a/webservice 模块"</b>：ADK 的 {@code a2a/README.md}
 * 描述了它，但 1.13.0 的源码克隆里只有 {@code a2a/src}（agent/common/converters/executor），
 * 没有 webservice 模块，Maven 中央仓库也没有对应制品。所以用 ADK 真正提供的东西自己接：
 * {@link AgentExecutor}（ADK 侧）+ {@link DefaultRequestHandler}（a2a SDK 侧），
 * 传输层由 {@link A2AController} 提供。
 */
@Configuration
public class A2AServerConfig {

    /** A2A 规范要求的发现路径。 */
    public static final String AGENT_CARD_PATH = "/.well-known/agent-card.json";

    /** JSON-RPC 端点（AgentCard.url 指向这里）。 */
    public static final String RPC_PATH = "/a2a/v1";

    /**
     * 暴露给远端的 Agent。
     *
     * <p>本服务只有一个根 Agent（{@code resume_agent}），没有 coordinator：
     * 能被发到 8083 的消息，调用方已经判定是简历请求。
     */
    @Bean
    public BaseAgent a2aAgent(ResumeAgentFactory factory) {
        return factory.create();
    }

    /**
     * AgentCard —— 发现元数据。
     *
     * <p>description 直接复用 Agent 的 description：两处口径必须一致，否则
     * D17 的 coordinator 看到的是"卡片说 A、Agent 自己是 B"，路由会摇摆。
     */
    @Bean
    public AgentCard resumeAgentCard(
            @Value("${mockverse.a2a.public-url:http://localhost:8083}") String publicUrl) {
        return new AgentCard.Builder()
                .name("mockverse-resume")
                .description(ResumeAgentPrompts.DESCRIPTION)
                .url(publicUrl + RPC_PATH)
                .version("0.1.0")
                .capabilities(new AgentCapabilities.Builder()
                        .streaming(true)
                        .pushNotifications(false)
                        // 诊断记录要落库归档，保留状态迁移历史便于回溯
                        .stateTransitionHistory(true)
                        .build())
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .skills(List.of(new AgentSkill.Builder()
                        .id("resume-diagnosis")
                        .name("简历诊断")
                        .description("对简历原文做结构化诊断：总分 + 三个维度分 + 逐条问题（带原文引用），"
                                + "结果落库 MySQL；用户粘贴简历或要求点评简历时调用")
                        .tags(List.of("resume", "diagnosis", "java", "backend"))
                        .examples(List.of(
                                "帮我诊断一下这份简历：……",
                                "我的简历有什么问题？",
                                "这段项目经历该怎么写？"))
                        .build()))
                .build();
    }

    @Bean
    public InMemorySessionService a2aSessionService() {
        return new InMemorySessionService();
    }

    /**
     * ADK → A2A 的适配器。
     *
     * <p>默认的 ARTIFACT_PER_RUN：一次 A2A 调用只产出一条 artifact，但它的 parts 是该 run
     * 里所有事件的内容按顺序追加——工具调用（diagnose_resume）的 functionCall/functionResponse
     * 也会作为 data part 出现在里面。取文本时必须按 part 类型过滤（D17 的 RemoteA2AAgent 同理）。
     */
    @Bean
    public AgentExecutor adkAgentExecutor(BaseAgent a2aAgent, InMemorySessionService a2aSessionService) {
        // 注意是 new Builder() 而不是 AgentExecutor.builder()：ADK 1.13.0 的 AgentExecutor
        // 只有私有构造 + 公开静态内部类 Builder，没有静态工厂方法。
        return new AgentExecutor.Builder()
                .agent(a2aAgent)
                .appName("mockverse-resume")
                .sessionService(a2aSessionService)
                // 必须显式给：AgentExecutor 会把 null 原样传给 Runner.Builder，覆盖其默认值，
                // Runner.build() 随即报 "Artifact service must be provided"。
                .artifactService(new InMemoryArtifactService())
                .agentExecutorConfig(AgentExecutorConfig.builder().build())
                .build();
    }

    /**
     * JSON-RPC 方法处理器。
     *
     * <p><b>为什么要套一层 {@link A2AHandler}</b>：{@code DefaultRequestHandler} 的两个超时
     * 字段带 {@code @jakarta.inject.Inject} + MicroProfile {@code @ConfigProperty}（为 Quarkus/CDI
     * 设计），Spring 的 AutowiredAnnotationBeanPostProcessor 也认 {@code @Inject}，会去找
     * {@code int} 类型的 bean 来注入——启动直接失败。包一层自己的类型即可绕开。
     *
     * <p><b>代价是这两个字段的默认值要自己补</b>：CDI 不注入就是 0，实测 0 会让 message/send
     * 立刻返回 "Timeout waiting for consumption to complete"。这里按 SDK 的
     * {@code @ConfigProperty} 默认值（30s / 5s）回填。
     */
    @Bean
    public A2AHandler a2aRequestHandler(
            AgentExecutor adkAgentExecutor,
            @Value("${mockverse.a2a.agent-timeout-seconds:30}") int agentTimeoutSeconds,
            @Value("${mockverse.a2a.consumption-timeout-seconds:5}") int consumptionTimeoutSeconds) {
        InMemoryTaskStore taskStore = new InMemoryTaskStore();
        InMemoryPushNotificationConfigStore pushConfigStore = new InMemoryPushNotificationConfigStore();
        DefaultRequestHandler handler = new DefaultRequestHandler(
                adkAgentExecutor,
                taskStore,
                new InMemoryQueueManager(taskStore),
                pushConfigStore,
                new BasePushNotificationSender(pushConfigStore),
                Executors.newCachedThreadPool());
        setTimeout(handler, "agentCompletionTimeoutSeconds", agentTimeoutSeconds);
        setTimeout(handler, "consumptionCompletionTimeoutSeconds", consumptionTimeoutSeconds);
        return new A2AHandler(handler);
    }

    /** 仅用于持有 {@link DefaultRequestHandler}，绕开 Spring 对 SDK 字段的 CDI 注入（见上）。 */
    public record A2AHandler(DefaultRequestHandler requestHandler) {
    }

    /**
     * 反射回填 SDK 的包私有超时字段（没有 setter，构造器也不接收）。
     *
     * <p>字段名变了就立刻抛异常而不是静默用 0——0 的表现是"每次调用都超时"，
     * 排查成本远高于启动即失败。
     */
    private static void setTimeout(DefaultRequestHandler handler, String field, int seconds) {
        try {
            Field f = DefaultRequestHandler.class.getDeclaredField(field);
            f.setAccessible(true);
            f.setInt(handler, seconds);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "A2A SDK 的 DefaultRequestHandler." + field + " 不存在或不可写，"
                            + "SDK 版本可能已变；请重新核对 io.a2a.server.requesthandlers.DefaultRequestHandler",
                    e);
        }
    }
}
