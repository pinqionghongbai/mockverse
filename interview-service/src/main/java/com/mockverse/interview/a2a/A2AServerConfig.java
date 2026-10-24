package com.mockverse.interview.a2a;

import com.google.adk.a2a.executor.AgentExecutor;
import com.google.adk.a2a.executor.AgentExecutorConfig;
import com.google.adk.agents.BaseAgent;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.sessions.InMemorySessionService;
import com.mockverse.interview.agent.MultiAgentFactory;
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
 * <p><b>为什么不是"引入 adk-java 的 a2a/webservice 模块"</b>：ADK 的 {@code a2a/README.md}
 * 描述了 {@code a2a/webservice}（Spring Boot 传输模块）与 {@code A2ASendMessageExecutor}，
 * 但 1.13.0 的源码克隆里**只有 {@code a2a/src}**（agent/common/converters/executor），
 * 没有 webservice 模块，Maven 中央仓库也没有对应制品。所以这里用 ADK 真正提供的东西自己接：
 * <ul>
 *   <li>{@link AgentExecutor}（ADK 侧）——把 Runner 适配成 a2a SDK 的 AgentExecutor</li>
 *   <li>{@link DefaultRequestHandler}（a2a SDK 侧）——完整的 JSON-RPC 方法实现</li>
 * </ul>
 * 传输层（HTTP + JSON-RPC 信封）由 {@link A2AController} 提供，等价于官方 webservice 的职责。
 *
 * <p><b>为什么不直接把 runner 暴露出去</b>：DefaultRequestHandler 负责 Task 生命周期
 * （submit / work / complete）、事件队列、取消与推送通知。自己实现这套状态机是重复造轮子，
 * 而且会和 D17 的 RemoteA2AAgent（按 A2A 规范发报文）对不上。
 */
@Configuration
public class A2AServerConfig {

    /** A2A 规范要求的发现路径。 */
    public static final String AGENT_CARD_PATH = "/.well-known/agent-card.json";

    /** JSON-RPC 端点（AgentCard.url 指向这里）。 */
    public static final String RPC_PATH = "/a2a/v1";

    /**
     * 暴露给远端的 Agent 树。
     *
     * <p>用 {@link MultiAgentFactory#create()} 的完整树（root_coordinator + interviewer_agent）：
     * 远端通过 A2A 发来一条用户消息，coordinator 照常做进程内路由。D17 的
     * RemoteA2AAgent 把知识/简历意图再转出去，形成跨进程的两级路由。
     */
    @Bean
    public BaseAgent a2aAgent(MultiAgentFactory factory) {
        return factory.create();
    }

    /**
     * AgentCard —— 发现元数据。
     *
     * <p><b>description / skills 写的是"什么时候找我"</b>，不是"我是什么"：D17 的
     * RemoteA2AAgent 靠这张卡决定把哪些用户意图转给本服务（与 D12 子 Agent 的
     * description 同理）。写得太泛（如"面试相关服务"）会让 coordinator 把知识问题
     * 也转过来。
     */
    @Bean
    public AgentCard interviewAgentCard(
            @Value("${mockverse.a2a.public-url:http://localhost:8081}") String publicUrl) {
        return new AgentCard.Builder()
                .name("mockverse-interview")
                .description("MockVerse 面试官服务。当用户希望开始/继续一场技术模拟面试、"
                        + "作答面试题、要求面试官评价答案或追问时找我。"
                        + "不做知识库答疑，也不做简历诊断——那两类有各自的服务。")
                .url(publicUrl + RPC_PATH)
                .version("0.1.0")
                .capabilities(new AgentCapabilities.Builder()
                        .streaming(true)
                        .pushNotifications(false)
                        // 面试是有状态的：同一 contextId 的多轮对话需要历史
                        .stateTransitionHistory(true)
                        .build())
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .skills(List.of(new AgentSkill.Builder()
                        .id("mock-interview")
                        .name("技术模拟面试")
                        .description("按岗位/方向出题，逐轮追问并评价候选人回答，"
                                + "共 5 轮；用户说\"开始面试\"或直接答题时调用")
                        .tags(List.of("interview", "java", "backend"))
                        .examples(List.of(
                                "开始一场 Java 后端面试",
                                "HashMap 的扩容机制是怎样的？",
                                "帮我评价一下刚才这个回答"))
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
     * <p>{@code outputMode} 用默认的 ARTIFACT_PER_RUN：一次 A2A 调用只产出**一条** artifact，
     * 但这条 artifact 的 parts 是该 run 里**所有事件**的内容按顺序追加的结果——
     * 实测一次带工具调用的面试开场返回 3 个 part（functionCall / functionResponse / 最终文本）。
     * 也就是说工具调用的中间产物**会**随 artifact 一起发给对端，只是不表现为独立的 artifact
     * 或事件。D17 的 RemoteA2AAgent 取值时要按 part 类型过滤，只取文本。
     */
    @Bean
    public AgentExecutor adkAgentExecutor(BaseAgent a2aAgent, InMemorySessionService a2aSessionService) {
        // 注意是 new Builder() 而不是 AgentExecutor.builder()：ADK 1.13.0 的 AgentExecutor
        // 只有私有构造 + 公开静态内部类 Builder，没有静态工厂方法（官方 a2a_server 示例同写法）。
        return new AgentExecutor.Builder()
                .agent(a2aAgent)
                .appName("mockverse-interview")
                .sessionService(a2aSessionService)
                // 必须显式给：AgentExecutor 会把它（哪怕是 null）原样传给 Runner.Builder，
                // 覆盖掉 Runner 内部的默认值，Runner.build() 随即报 "Artifact service must be provided"。
                // A2A 的输出 artifact 也落在这里，面试一轮的最终答案即一条 artifact。
                .artifactService(new InMemoryArtifactService())
                .agentExecutorConfig(AgentExecutorConfig.builder().build())
                .build();
    }

    /**
     * JSON-RPC 方法处理器。
     *
     * <p>四个协作对象都是 InMemory 实现：本项目的 A2A 调用是"请求-响应"式的短任务，
     * Task 与事件队列不需要跨进程/跨重启持久化。D20 接 Kafka 后若有长任务再换实现。
     *
     * <p><b>为什么套一层 {@link A2AHandler} 而不是直接注册 {@code DefaultRequestHandler} bean</b>：
     * 该类的两个超时字段带 {@code @jakarta.inject.Inject} + MicroProfile {@code @ConfigProperty}
     * （为 Quarkus/CDI 设计）。Spring 的 AutowiredAnnotationBeanPostProcessor 也认
     * {@code @Inject}，于是会去找一个 {@code int} 类型的 bean 来注入——
     * 启动直接报 "required a bean of type 'int' that could not be found"。
     * 把实例包在自己的类型里，Spring 只后处理这个空壳，不会碰 SDK 的字段。
     *
     * <p><b>代价是这两个字段的默认值也得自己补</b>：字段声明处没有初始化器，CDI 不注入就是 0。
     * 实测 0 会让 message/send 立刻返回 "Timeout waiting for consumption to complete"。
     * 这里按 SDK 的 {@code @ConfigProperty} 默认值（30s / 5s）回填——不是我们挑的数字，
     * 而是把 CDI 环境下本来就该有的值补上。{@link #create} 静态工厂里的 5s/2s 更紧，
     * 对一次 3~5 秒的 DeepSeek 调用太冒险，所以没用它。
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
