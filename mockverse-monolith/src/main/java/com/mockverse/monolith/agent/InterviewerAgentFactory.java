package com.mockverse.monolith.agent;

import com.google.adk.agents.LlmAgent;
import com.google.adk.models.langchain4j.LangChain4j;
import com.google.adk.tools.FunctionTool;
import com.mockverse.monolith.config.InterviewProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 面试官 Agent 工厂。
 *
 * <p>之所以做成"工厂 + 版本参数"而不是单例 Bean: D9 下午要做 A-B-C 三版提示词的对照，
 * 需要在同一进程里并存三个 Agent 实例。正常运行时只构建配置里指定的那一版。
 */
@Component
@RequiredArgsConstructor
public class InterviewerAgentFactory {

    private final LangChain4j deepSeekModel;
    private final QuestionTools questionTools;
    private final InterviewProperties properties;

    /** 用配置中的默认版本构建。 */
    public LlmAgent create() {
        return create(properties.getPromptVersion());
    }

    /** 指定提示词版本构建（v1/v2/v3）。 */
    public LlmAgent create(String promptVersion) {
        return LlmAgent.builder()
                .name("interviewer_agent")
                .description("MockVerse Java 后端模拟面试官，负责出题、评价回答并追问。")
                .model(deepSeekModel)
                .instruction(InterviewerPrompts.of(promptVersion))
                // 实例方法必须传 instance；静态方法才可只传 Class
                .tools(FunctionTool.create(questionTools, "pickQuestion"))
                .build();
    }
}
