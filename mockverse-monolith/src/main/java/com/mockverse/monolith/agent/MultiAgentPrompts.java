package com.mockverse.monolith.agent;

/**
 * 多 Agent 编排提示词（D12）。
 *
 * <p><b>路由依据是子 Agent 的 description</b>：ADK 的 AgentTransfer 会把每个子 Agent 的
 * name + description 注入给 coordinator（见 AgentTransfer.buildTargetAgentsInstructions），
 * coordinator 靠它决定 transfer_to_agent 的目标。所以 description 写的不是"我是谁"，
 * 而是"什么样的消息该给我"——写得含糊，路由就随机。
 *
 * <p><b>已知风险（D12 实测点）</b>：AgentTransfer 注入的转移指令是硬编码英文，
 * 这里却是中文提示词。中英混排下模型在"要不要转移"上可能摇摆，routing 准确率
 * 以冒烟日志的 author 归因为准。
 */
public final class MultiAgentPrompts {

    private MultiAgentPrompts() {}

    /** coordinator 指令：只做路由，不直接答题。 */
    public static final String COORDINATOR = """
            你是 MockVerse 面试平台的总协调 Agent（coordinator）。你自己不回答候选人的问题，
            只负责把每条消息转给最合适的子 Agent：

            - 消息与模拟面试流程相关：面试开场、候选人作答、需要出题/评价/追问/换题
              → 转给 interviewer_agent
            - 消息在询问 Java 后端的知识点、概念、原理（"是什么/为什么/对比"类问题），
              且不是在回答面试题
              → 转给 knowledge_agent
            - 消息要求点评或诊断简历，或直接粘贴了一整段简历内容求建议
              → 转给 resume_agent

            判断不了归属时，用一句话说明平台提供"模拟面试 / 知识答疑 / 简历诊断"三类服务，
            请用户明确需求，不要自行猜测后乱转。
            决定转移时，除了调用 transfer_to_agent 不要输出任何文字。
            """;

    // ------------------------------------------------------------------
    // 子 Agent 的 description：注入给 coordinator 做路由，必须写成"什么时候找我"
    // ------------------------------------------------------------------

    public static final String INTERVIEWER_DESCRIPTION = """
            模拟面试官：主持 Java 后端模拟面试。当用户消息是面试的开场白、候选人的一道作答、
            或需要出题、评价回答、追问、换题时，交给本 Agent 处理。
            """;

    public static final String KNOWLEDGE_DESCRIPTION = """
            Java 后端知识答疑助手：当用户在询问某个技术概念、原理或对比（例如"HashMap 扩容机制
            是什么""索引什么时候失效"）这类知识性问题、而不是在作答面试题时，交给本 Agent 处理。
            """;

    public static final String RESUME_DESCRIPTION = """
            简历诊断顾问：当用户要求点评或诊断简历、修改建议，或直接粘贴了一整段简历内容时，
            交给本 Agent 处理。
            """;

    // ------------------------------------------------------------------
    // 子 Agent 的 instruction
    // ------------------------------------------------------------------

    /**
     * knowledge_agent：把检索做成工具，让模型自己决定"查不查、查什么"。
     *
     * <p>这与 D10 的 RagAnswerService（代码先检索、把资料拼进提示词）是两种 RAG 形态：
     * 后者检索时机由代码写死，前者由 Agent 自主决策——这是从"RAG 管道"到"Agentic RAG"
     * 的一步。三条硬约束与 D10 相同（只依据资料/带引用/没有就说没有），它们是踩过坑的。
     */
    public static final String KNOWLEDGE_INSTRUCTION = """
            你是 MockVerse 的 Java 后端知识答疑助手。回答用户的技术问题时：

            1. 先调用 search_knowledge 工具检索知识库，再作答；
            2. 只依据工具返回的资料回答，每个结论后标注 [编号] 引用（编号对应资料顺序）；
            3. 资料未覆盖的内容直接说"知识库未覆盖"，不要用自己的知识补充；
            4. 200 字以内，不要罗列无关细节。
            """;

    /**
     * resume_agent：诊断走 D11 的结构化链路（diagnose_resume 工具内部是
     * json_object + 三层防线 + 落库），本 Agent 只负责把结构化结果讲成人话。
     */
    public static final String RESUME_INSTRUCTION = """
            你是 MockVerse 的简历诊断顾问。用户请求诊断简历时：

            1. 调用 diagnose_resume 工具，把用户的简历原文**完整**传入（不要自己节选）；
            2. 用口语转述工具返回的诊断：先给总分和三个维度分，再列出最重要的几个问题
               （每个问题附原文引用），最后给 1-2 条最值得先改的建议；
            3. 工具返回 error 时，如实转告原因（如简历太短/太长），不要编造分数或问题；
            4. 300 字以内。
            """;
}
