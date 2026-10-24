package com.mockverse.resume.agent;

/**
 * 简历 Agent 的提示词（D16 自单体 {@code MultiAgentPrompts} 迁移）。
 *
 * <p>注意与 {@link ResumeDiagnosisPrompts} 的分工：那份是**诊断链路内部**的提示词
 * （要求模型只输出 json，由 {@code ResumeDiagnosisService} 直接调用）；
 * 这份是**外层 Agent** 的提示词（决定调不调工具、怎么把结果讲成人话）。
 * 两者不能合并——一个是 JSON 生产者，一个是对话者。
 *
 * <p><b>description 写的是"什么时候找我"</b>：D17 的 RemoteA2AAgent 会把这段文字作为
 * 工具描述交给面试服务的 coordinator 做跨进程路由。
 */
public final class ResumeAgentPrompts {

    private ResumeAgentPrompts() {}

    /** 供 AgentCard 的 description 复用，口径与 Agent 的 description 保持一致。 */
    public static final String DESCRIPTION = """
            简历诊断顾问：当用户要求点评或诊断简历、修改建议，或直接粘贴了一整段简历内容时，
            交给本 Agent 处理。
            """;

    /**
     * resume_agent 的指令：诊断走 {@code diagnose_resume} 工具（内部是
     * json_object + 三层防线 + 落库），本 Agent 只负责把结构化结果讲成人话。
     */
    public static final String INSTRUCTION = """
            你是 MockVerse 的简历诊断顾问。用户请求诊断简历时：

            1. 调用 diagnose_resume 工具，把用户的简历原文**完整**传入（不要自己节选）；
            2. 用口语转述工具返回的诊断：先给总分和三个维度分，再列出最重要的几个问题
               （每个问题附原文引用），最后给 1-2 条最值得先改的建议；
            3. 工具返回 error 时，如实转告原因（如简历太短/太长），不要编造分数或问题；
            4. 300 字以内。
            """;
}
