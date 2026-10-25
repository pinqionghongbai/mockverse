package com.mockverse.interview.agent;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.models.langchain4j.LangChain4j;
import com.google.adk.tools.FunctionTool;
import com.mockverse.interview.a2a.A2AHttpCalls;
import com.mockverse.interview.a2a.A2ARemoteAgent;
import com.mockverse.interview.a2a.A2AServerConfig;
import com.mockverse.interview.config.InterviewProperties;
import com.mockverse.interview.config.RemoteAgentProperties;
import io.a2a.client.http.A2ACardResolver;
import io.a2a.client.http.JdkA2AHttpClient;
import io.a2a.spec.A2AClientError;
import io.a2a.spec.AgentCard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 面试官编排树工厂（D12 的进程内版本，D15 拆分，D17 用跨进程子 Agent 接回）。
 *
 * <p>拓扑: {@code root_coordinator}（只路由不答题）→ 三个子 Agent:
 * <ul>
 *   <li>{@code interviewer_agent} — 进程内 {@link LlmAgent}（0.7 + pick_question）</li>
 *   <li>{@code knowledge_agent} — 跨进程 {@link A2ARemoteAgent} → knowledge-service:8082</li>
 *   <li>{@code resume_agent} — 跨进程 {@link A2ARemoteAgent} → resume-service:8083（带 userId）</li>
 * </ul>
 *
 * <p><b>为什么在工厂里 {@code new} 而不把远端 Agent 注册成 Spring Bean</b>（D16 建议 5）：
 * {@code A2AServerConfig.a2aAgent} 是当前唯一的 {@code BaseAgent} bean，{@code adkAgentExecutor}
 * 靠参数名匹配才没歧义。再注册同类型 bean，任何"按类型注入 BaseAgent 且参数名不匹配"的代码
 * 都会启动失败。工厂内部创建则完全不碰容器。
 *
 * <p><b>卡片在 {@link #create()} 时解析一次并缓存</b>：{@code InterviewService} 构造时调一次、
 * {@code A2AServerConfig} 建 bean 时又调一次，共两次；缓存后只联网一次。
 * 卡片本身不刷新——远端改了 description 需要重启本服务（D16 建议 3 的代价，已知且可接受）。
 *
 * <p><b>不需要给子 Agent 单独建 Runner 或 ensureSession</b>: ADK 的 transfer 是同一
 * invocation 内的接力（BaseLlmFlow.followTransfer 把子 Agent 的 runAsync 接在转移事件之后），
 * 会话挂在 root 上，子 Agent 直接读写。
 *
 * <p><b>transfer_to_agent 是 ADK 自动注入的</b>: 只要 subAgents 非空，AgentTransfer
 * 就会注入该工具和目标清单——不需要（也不能）自己注册。
 */
@Slf4j
@Component
public class MultiAgentFactory {

    public static final String ROOT = "root_coordinator";
    public static final String INTERVIEWER = "interviewer_agent";
    public static final String KNOWLEDGE = "knowledge_agent";
    public static final String RESUME = "resume_agent";

    /** A2A 规范的服务发现路径。与 {@link A2AServerConfig#AGENT_CARD_PATH} 同值。 */
    private static final String AGENT_CARD_PATH = "/.well-known/agent-card.json";

    /**
     * 启动期拉卡片的上限：本机/同网段的 GET 远小于 1s，10s 已足够宽裕。
     *
     * <p>SDK 的 HTTP 客户端没有超时（见 {@code A2AHttpCalls}），远端"连得上但不回"会让
     * interview-service 永远卡在启动阶段——既不是 fail-fast 也不是可用状态，只能靠人 Ctrl-C。
     */
    private static final Duration CARD_FETCH_TIMEOUT = Duration.ofSeconds(10);

    private final LangChain4j deepSeekModel;
    private final QuestionTools questionTools;
    private final InterviewProperties properties;
    private final RemoteAgentProperties remoteProperties;

    /** 远端卡片缓存：key = 基地址。解析失败不缓存，下次调用可重试。 */
    private final Map<String, AgentCard> cardCache = new ConcurrentHashMap<>();

    public MultiAgentFactory(LangChain4j deepSeekModel,
                             QuestionTools questionTools,
                             InterviewProperties properties,
                             RemoteAgentProperties remoteProperties) {
        this.deepSeekModel = deepSeekModel;
        this.questionTools = questionTools;
        this.properties = properties;
        this.remoteProperties = remoteProperties;
    }

    public LlmAgent create() {
        LlmAgent interviewer = LlmAgent.builder()
                .name(INTERVIEWER)
                .description(MultiAgentPrompts.INTERVIEWER_DESCRIPTION)
                .model(deepSeekModel)
                .instruction(InterviewerPrompts.of(properties.getPromptVersion()))
                .tools(FunctionTool.create(questionTools, "pickQuestion"))
                .build();

        BaseAgent knowledge = new A2ARemoteAgent(
                KNOWLEDGE, MultiAgentPrompts.KNOWLEDGE_DESCRIPTION,
                resolveCard(remoteProperties.getKnowledgeUrl()),
                // 知识答疑不需要用户身份
                false,
                remoteTimeout());

        BaseAgent resume = new A2ARemoteAgent(
                RESUME, MultiAgentPrompts.RESUME_DESCRIPTION,
                resolveCard(remoteProperties.getResumeUrl()),
                // 简历诊断要归档到真实用户名下，把 session.userId 放进 params.metadata
                true,
                remoteTimeout());

        return LlmAgent.builder()
                .name(ROOT)
                .description("MockVerse 面试服务协调 Agent：把面试流程的消息转给面试官，"
                        + "知识答疑转给知识库服务，简历诊断转给简历服务。")
                .model(deepSeekModel)
                .instruction(MultiAgentPrompts.COORDINATOR)
                .subAgents(interviewer, knowledge, resume)
                .build();
    }

    /**
     * 远端调用上限；配置成 0 或负数表示不设上限（只用于压测/排障，见 {@code A2ARemoteAgent}）。
     */
    private Duration remoteTimeout() {
        int seconds = remoteProperties.getTimeoutSeconds();
        return seconds > 0 ? Duration.ofSeconds(seconds) : null;
    }

    /**
     * 拉远端 AgentCard。
     *
     * <p>失败时抛带地址的异常而不是让 NPE 冒出去——这张卡片是"服务能不能起来"的前置条件，
     * 报错里没有地址，排查就得靠猜端口。最常见的两个原因都写进消息里。
     */
    private AgentCard resolveCard(String baseUrl) {
        AgentCard cached = cardCache.get(baseUrl);
        if (cached != null) {
            return cached;
        }
        try {
            AgentCard card = A2AHttpCalls.call("从 " + baseUrl + " 解析 AgentCard", CARD_FETCH_TIMEOUT,
                    () -> new A2ACardResolver(
                            new JdkA2AHttpClient(), baseUrl, baseUrl + AGENT_CARD_PATH).getAgentCard());
            cardCache.put(baseUrl, card);
            log.info("已解析远端 AgentCard: {} @ {}", card.name(), baseUrl);
            return card;
        } catch (A2AClientError e) {
            // 只接 A2AClientError 这一个父类：A2AClientJSONError 是它的子类，
            // 多 catch 并列写会被编译器判为"alternatives related by subclassing"
            throw new IllegalStateException(
                    "无法从 " + baseUrl + AGENT_CARD_PATH + " 解析 AgentCard。"
                            + "请确认该服务已启动（interview-service 必须在 8082/8083 之后启动），"
                            + "且地址未被占用/改端口。", e);
        }
    }
}
