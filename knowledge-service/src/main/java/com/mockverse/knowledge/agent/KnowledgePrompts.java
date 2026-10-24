package com.mockverse.knowledge.agent;

/**
 * 知识 Agent 的提示词（D16 自单体 {@code MultiAgentPrompts} 迁移）。
 *
 * <p><b>description 写的是"什么时候找我"</b>：D17 的 RemoteA2AAgent 会把这段文字作为
 * 工具描述交给面试服务的 coordinator 做跨进程路由——写成"我是知识库"这种自述，
 * coordinator 就不知道该在什么时机把消息转过来。
 */
public final class KnowledgePrompts {

    private KnowledgePrompts() {}

    /** 供 AgentCard 的 skill description 复用，口径与 Agent 的 description 保持一致。 */
    public static final String DESCRIPTION = """
            Java 后端知识答疑助手：当用户询问某个技术概念、原理或对比（例如"HashMap 扩容机制
            是什么""索引什么时候失效"）这类知识性问题、而不是在作答面试题时，交给本 Agent 处理。
            """;

    /**
     * knowledge_agent 的指令：把检索做成工具，让模型自己决定"查不查、查什么"。
     *
     * <p>这与 D10 的 RagAnswerService（代码先检索、把资料拼进提示词）是两种 RAG 形态：
     * 后者检索时机由代码写死，前者由 Agent 自主决策——这是从"RAG 管道"到"Agentic RAG"
     * 的一步。三条硬约束与 D10 相同（只依据资料/带引用/没有就说没有），它们是踩过坑的。
     */
    public static final String INSTRUCTION = """
            你是 MockVerse 的 Java 后端知识答疑助手。回答用户的技术问题时：

            1. 先调用 search_knowledge 工具检索知识库，再作答；
            2. 只依据工具返回的资料回答，每个结论后标注 [编号] 引用（编号对应资料顺序）；
            3. 资料未覆盖的内容直接说"知识库未覆盖"，不要用自己的知识补充；
            4. 200 字以内，不要罗列无关细节。
            """;
}
