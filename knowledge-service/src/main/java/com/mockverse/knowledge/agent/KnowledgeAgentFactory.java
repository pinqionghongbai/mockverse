package com.mockverse.knowledge.agent;

import com.google.adk.agents.LlmAgent;
import com.google.adk.models.langchain4j.LangChain4j;
import com.google.adk.tools.FunctionTool;
import org.springframework.stereotype.Component;

/**
 * 知识 Agent 工厂（D16）。
 *
 * <p>单体时代这个 Agent 是 coordinator 的一个子 Agent，靠进程内 transfer 调用；
 * 拆分后它成为本服务的"根 Agent"，由 A2A 暴露给外部（{@code /.well-known/agent-card.json}
 * 描述它，{@code /a2a/v1} 驱动它）。D17 面试服务用 RemoteA2AAgent 把它接回协调树，
 * 调用方式变跨进程，Agent 本身的指令与工具不变。
 *
 * <p><b>为什么这里只有一个 Agent、没有 coordinator</b>：服务边界即路由边界。
 * 一条消息能被发到这个服务，说明调用方已经判定它是知识问题——再让本地 coordinator
 * 判断一次是重复劳动，还会多一次 LLM 调用。
 */
@Component
public class KnowledgeAgentFactory {

    public static final String NAME = "knowledge_agent";

    private final LangChain4j knowledgeModel;
    private final KnowledgeTools knowledgeTools;

    public KnowledgeAgentFactory(LangChain4j knowledgeModel, KnowledgeTools knowledgeTools) {
        this.knowledgeModel = knowledgeModel;
        this.knowledgeTools = knowledgeTools;
    }

    public LlmAgent create() {
        return LlmAgent.builder()
                .name(NAME)
                .description(KnowledgePrompts.DESCRIPTION)
                .model(knowledgeModel)
                .instruction(KnowledgePrompts.INSTRUCTION)
                .tools(FunctionTool.create(knowledgeTools, "searchKnowledge"))
                .build();
    }
}
