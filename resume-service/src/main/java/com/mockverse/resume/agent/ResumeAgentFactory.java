package com.mockverse.resume.agent;

import com.google.adk.agents.LlmAgent;
import com.google.adk.models.langchain4j.LangChain4j;
import com.google.adk.tools.FunctionTool;
import org.springframework.stereotype.Component;

/**
 * 简历 Agent 工厂（D16）。
 *
 * <p>单体时代这个 Agent 是 coordinator 的子 Agent，靠进程内 transfer 调用；
 * 拆分后它成为本服务的"根 Agent"，由 A2A 暴露给外部。D17 面试服务用 RemoteA2AAgent
 * 把它接回协调树，Agent 本身的指令与工具不变。
 *
 * <p><b>为什么只有一个 Agent、没有 coordinator</b>：服务边界即路由边界。消息能被发到
 * 8083，说明调用方已判定它是简历请求，本地再判断一次是重复劳动。
 */
@Component
public class ResumeAgentFactory {

    public static final String NAME = "resume_agent";

    private final LangChain4j resumeModel;
    private final ResumeTools resumeTools;

    public ResumeAgentFactory(LangChain4j resumeModel, ResumeTools resumeTools) {
        this.resumeModel = resumeModel;
        this.resumeTools = resumeTools;
    }

    public LlmAgent create() {
        return LlmAgent.builder()
                .name(NAME)
                .description(ResumeAgentPrompts.DESCRIPTION)
                .model(resumeModel)
                .instruction(ResumeAgentPrompts.INSTRUCTION)
                .tools(FunctionTool.create(resumeTools, "diagnoseResume"))
                .build();
    }
}
