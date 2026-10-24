package com.mockverse.interview.agent;

import com.google.adk.agents.LlmAgent;
import com.google.adk.models.langchain4j.LangChain4j;
import com.google.adk.tools.FunctionTool;
import com.mockverse.interview.config.InterviewProperties;
import org.springframework.stereotype.Component;

/**
 * 面试官编排树工厂（D15 自 D12 的 MultiAgentFactory 机械裁剪而来）。
 *
 * <p>拓扑: {@code root_coordinator}（只路由不答题）→ 唯一子 Agent:
 * <ul>
 *   <li>{@code interviewer_agent} — 复用 D9 的面试官（0.7 + pick_question）</li>
 * </ul>
 *
 * <p>单体时代的 {@code knowledge_agent} / {@code resume_agent} 已随 D15 拆分迁往
 * knowledge-service (8082) / resume-service (8083)，D17 起用 RemoteA2AAgent 接回。
 * 在接回之前，coordinator 对知识/简历意图的口径见 {@link MultiAgentPrompts#COORDINATOR}：
 * 如实告知"已拆分、接入中"，不假装本地能力还在。
 *
 * <p><b>不需要给子 Agent 单独建 Runner 或 ensureSession</b>: ADK 的 transfer 是同一
 * invocation 内的接力（BaseLlmFlow.followTransfer 把子 Agent 的 runAsync 接在转移事件
 * 之后），会话挂在 root 上，子 Agent 直接读写。
 *
 * <p><b>transfer_to_agent 是 ADK 自动注入的</b>: 只要 subAgents 非空，AgentTransfer
 * 就会注入该工具和目标清单——不需要（也不能）自己注册。
 *
 * <p>故障注入链路（create(faultySubAgent, sleepMillis) + FaultyChatModel）留在单体
 * 的冒烟基建里，不随拆分迁移——smoke 依赖完整 Agent 树，见拆分方案第四节原则 5。
 */
@Component
public class MultiAgentFactory {

    public static final String ROOT = "root_coordinator";
    public static final String INTERVIEWER = "interviewer_agent";

    private final LangChain4j deepSeekModel;
    private final QuestionTools questionTools;
    private final InterviewProperties properties;

    public MultiAgentFactory(LangChain4j deepSeekModel,
                             QuestionTools questionTools,
                             InterviewProperties properties) {
        this.deepSeekModel = deepSeekModel;
        this.questionTools = questionTools;
        this.properties = properties;
    }

    public LlmAgent create() {
        LlmAgent interviewer = LlmAgent.builder()
                .name(INTERVIEWER)
                .description(MultiAgentPrompts.INTERVIEWER_DESCRIPTION)
                .model(deepSeekModel)
                .instruction(InterviewerPrompts.of(properties.getPromptVersion()))
                .tools(FunctionTool.create(questionTools, "pickQuestion"))
                .build();

        return LlmAgent.builder()
                .name(ROOT)
                .description("MockVerse 面试服务协调 Agent：把模拟面试流程的消息转给面试官；"
                        + "知识答疑与简历诊断已拆分至独立服务，本服务不再处理这两类意图。")
                .model(deepSeekModel)
                .instruction(MultiAgentPrompts.COORDINATOR)
                .subAgents(interviewer)
                .build();
    }
}
