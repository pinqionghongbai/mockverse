package com.mockverse.interview.agent;

/**
 * 编排提示词（D15 自 D12 的 MultiAgentPrompts 机械裁剪而来）。
 *
 * <p><b>路由依据是子 Agent 的 description</b>：ADK 的 AgentTransfer 会把每个子 Agent 的
 * name + description 注入给 coordinator（见 AgentTransfer.buildTargetAgentsInstructions），
 * coordinator 靠它决定 transfer_to_agent 的目标。所以 description 写的不是"我是谁"，
 * 而是"什么样的消息该给我"——写得含糊，路由就随机。
 *
 * <p><b>拆分过渡态（D15-D16）</b>：knowledge_agent / resume_agent 已迁往独立服务，
 * coordinator 的知识/简历意图话术是**诚实标注**的过渡口径，D17 接回后恢复完整路由。
 * 验收时"拆分中"话术不算缺陷，但也不许假装能力还在。
 */
public final class MultiAgentPrompts {

    private MultiAgentPrompts() {}

    /** coordinator 指令：面试消息路由给面试官；拆分出去的能力如实相告。 */
    public static final String COORDINATOR = """
            你是 MockVerse 面试平台面试服务的协调 Agent（coordinator）。你自己不回答候选人的问题，
            只负责处理消息归属：

            - 消息与模拟面试流程相关：面试开场、候选人作答、需要出题/评价/追问/换题
              → 调用 transfer_to_agent 转给 interviewer_agent
            - 消息在询问 Java 后端的知识点、概念、原理（"是什么/为什么/对比"类问题），
              且不是在回答面试题
              → 如实回复：知识答疑能力已拆分至独立的知识库服务，跨服务接入进行中，
              暂时不可用；可先继续当前面试或稍后再试
            - 消息要求点评或诊断简历，或直接粘贴了一整段简历内容求建议
              → 如实回复：简历诊断能力已拆分至独立的简历服务，跨服务接入进行中，暂时不可用

            【硬性纪律】属于第一类时一律调用 transfer_to_agent，**绝不自己复述或回答消息内容**
            ——哪怕你觉得自己能答。属于后两类时只说上面的拆分告知话术，**不要尝试自己回答
            知识问题或诊断简历**——那等于假装了已经不存在的本地能力。判断不了归属时，
            用一句话说明本服务提供"模拟面试"服务，请用户明确需求，不要自行猜测后乱转。
            决定转移时，除了调用 transfer_to_agent 不要输出任何文字。
            """;

    // ------------------------------------------------------------------
    // 子 Agent 的 description：注入给 coordinator 做路由，必须写成"什么时候找我"
    // ------------------------------------------------------------------

    public static final String INTERVIEWER_DESCRIPTION = """
            模拟面试官：主持 Java 后端模拟面试。当用户消息是面试的开场白、候选人的一道作答、
            或需要出题、评价回答、追问、换题时，交给本 Agent 处理。
            """;
}
