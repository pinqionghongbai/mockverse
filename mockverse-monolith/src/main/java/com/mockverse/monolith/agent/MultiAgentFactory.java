package com.mockverse.monolith.agent;

import com.google.adk.agents.LlmAgent;
import com.google.adk.models.langchain4j.LangChain4j;
import com.google.adk.tools.FunctionTool;
import com.mockverse.monolith.config.InterviewProperties;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 多 Agent 编排树工厂（D12）。
 *
 * <p>拓扑: {@code root_coordinator}（只路由不答题）→ 三个子 Agent:
 * <ul>
 *   <li>{@code interviewer_agent} — 复用 D9 的面试官（0.7 + pick_question）</li>
 *   <li>{@code knowledge_agent} — 知识答疑（0.2 + search_knowledge，Agentic RAG）</li>
 *   <li>{@code resume_agent} — 简历诊断转述（0.7 对话；结构化诊断在工具内走 D11 的 0.3+json 链路）</li>
 * </ul>
 *
 * <p><b>不需要给子 Agent 单独建 Runner 或 ensureSession</b>: ADK 的 transfer 是同一
 * invocation 内的接力（BaseLlmFlow.followTransfer 把子 Agent 的 runAsync 接在转移事件
 * 之后），会话挂在 root 上，子 Agent 直接读写。
 *
 * <p><b>transfer_to_agent 是 ADK 自动注入的</b>: 只要 subAgents 非空，AgentTransfer
 * 就会注入该工具和目标清单——不需要（也不能）自己注册。
 */
@Component
public class MultiAgentFactory {

    public static final String ROOT = "root_coordinator";
    public static final String INTERVIEWER = "interviewer_agent";
    public static final String KNOWLEDGE = "knowledge_agent";
    public static final String RESUME = "resume_agent";

    private final LangChain4j deepSeekModel;
    private final ChatModel ragChatModel;
    private final QuestionTools questionTools;
    private final KnowledgeTools knowledgeTools;
    private final ResumeTools resumeTools;
    private final InterviewProperties properties;

    public MultiAgentFactory(LangChain4j deepSeekModel,
                             @Qualifier("ragChatModel") ChatModel ragChatModel,
                             QuestionTools questionTools,
                             KnowledgeTools knowledgeTools,
                             ResumeTools resumeTools,
                             InterviewProperties properties) {
        this.deepSeekModel = deepSeekModel;
        this.ragChatModel = ragChatModel;
        this.questionTools = questionTools;
        this.knowledgeTools = knowledgeTools;
        this.resumeTools = resumeTools;
        this.properties = properties;
    }

    /** 正常链路：三个子 Agent 全部接真模型。 */
    public LlmAgent create() {
        return create(null, 0);
    }

    /**
     * 故障注入链路（冒烟专用）：把指定子 Agent 的模型换成故障桩。
     *
     * @param faultySubAgent 要断掉的子 Agent 名（interviewer_agent / knowledge_agent / resume_agent），null 表示不断
     * @param sleepMillis    &gt;0 时用 slow 桩（睡眠，配合短超时触发 Reactive timeout），
     *                       =0 时用 fail 桩（立即抛异常）
     */
    public LlmAgent create(String faultySubAgent, long sleepMillis) {
        LlmAgent interviewer = LlmAgent.builder()
                .name(INTERVIEWER)
                .description(MultiAgentPrompts.INTERVIEWER_DESCRIPTION)
                .model(modelFor(INTERVIEWER, faultySubAgent, sleepMillis, deepSeekModel))
                .instruction(InterviewerPrompts.of(properties.getPromptVersion()))
                .tools(FunctionTool.create(questionTools, "pickQuestion"))
                .build();

        LlmAgent knowledge = LlmAgent.builder()
                .name(KNOWLEDGE)
                .description(MultiAgentPrompts.KNOWLEDGE_DESCRIPTION)
                .model(modelFor(KNOWLEDGE, faultySubAgent, sleepMillis,
                        // ragChatModel 是低温(0.2)的 langchain4j ChatModel，包一层适配成 ADK 模型。
                        // LangChain4j 是抽象类，只能走 builder（同 DeepSeekModelConfig 的做法）
                        LangChain4j.builder()
                                .chatModel(ragChatModel)
                                .modelName("deepseek-chat")
                                .build()))
                .instruction(MultiAgentPrompts.KNOWLEDGE_INSTRUCTION)
                .tools(FunctionTool.create(knowledgeTools, "searchKnowledge"))
                .build();

        LlmAgent resume = LlmAgent.builder()
                .name(RESUME)
                .description(MultiAgentPrompts.RESUME_DESCRIPTION)
                // resume_agent 的对话模型用 0.7（它只是转述）；结构化诊断的 0.3+json
                // 在 diagnose_resume 工具内部的 ResumeDiagnosisService 里
                .model(modelFor(RESUME, faultySubAgent, sleepMillis, deepSeekModel))
                .instruction(MultiAgentPrompts.RESUME_INSTRUCTION)
                .tools(FunctionTool.create(resumeTools, "diagnoseResume"))
                .build();

        return LlmAgent.builder()
                .name(ROOT)
                .description("MockVerse 总协调 Agent：按消息意图路由到面试官、知识答疑或简历诊断子 Agent。")
                .model(deepSeekModel)
                .instruction(MultiAgentPrompts.COORDINATOR)
                .subAgents(interviewer, knowledge, resume)
                .build();
    }

    /** 命中故障目标时用桩模型替换真模型。 */
    private static com.google.adk.models.BaseLlm modelFor(String agentName, String faultySubAgent,
                                                          long sleepMillis, com.google.adk.models.BaseLlm normal) {
        if (!agentName.equals(faultySubAgent)) {
            return normal;
        }
        return LangChain4j.builder()
                .chatModel(sleepMillis > 0
                        ? FaultyChatModel.slow(agentName, sleepMillis)
                        : FaultyChatModel.fail(agentName))
                .modelName(agentName + "-faulty")
                .build();
    }
}
