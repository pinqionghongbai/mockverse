package com.mockverse.resume.config;

import com.google.adk.models.langchain4j.LangChain4j;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * DeepSeek 模型 Bean（resume-service 副本，D15 机械拆分）。
 *
 * <p>走 [OI] 兼容协议, 由 langchain4j 直连 DeepSeek。本服务有两个 bean，**参数刻意不同**：
 *
 * <ul>
 *   <li>{@link #diagnosisChatModel} — 诊断链路用：**强制 JSON 输出 + temperature 0.3**。
 *       {@code responseFormat("json_object")} 是供应商侧的约束，比"提示词里求模型输出 JSON"
 *       可靠得多——后者在长输出时经常带一段"好的，以下是诊断结果："。注意这个开关打开后，
 *       提示词里必须出现 "json" 字样，否则 DeepSeek 直接返回 400（实测报错
 *       {@code Prompt must contain the word 'json' in some form}）。</li>
 *   <li>{@link #resumeModel} — D16 的 {@code resume_agent} 用：temperature 0.3 但**不开 JSON 模式**。
 *       Agent 要做的是"调工具 + 用口语转述"，强制 JSON 会把它的对话输出也压成 JSON，
 *       工具调用与转述都写不出来。结构化约束在工具内部（诊断链路）完成，不在这里。</li>
 * </ul>
 *
 * <p>API Key 只从环境变量读取, 不接受配置文件注入 (章程安全红线)。
 */
@Configuration
public class DeepSeekModelConfig {

    @Bean
    public ChatModel diagnosisChatModel(
            @Value("${mockverse.deepseek.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${mockverse.deepseek.model:deepseek-chat}") String modelName) {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey())
                .modelName(modelName)
                .temperature(0.3)
                .responseFormat("json_object")
                .build();
    }

    /**
     * ADK 适配层：{@code resume_agent} 用的模型。
     *
     * <p>必须同时挂 {@code streamingChatModel}：A2A 的 {@code message/stream} 走
     * {@code RunConfig.streamingMode=SSE}，LangChain4j 适配器只认流式模型，
     * 没挂就直接报 "StreamingChatModel is not configured"（D13 踩过）。
     */
    @Bean
    public LangChain4j resumeModel(
            @Value("${mockverse.deepseek.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${mockverse.deepseek.model:deepseek-chat}") String modelName) {
        return LangChain4j.builder()
                .chatModel(OpenAiChatModel.builder()
                        .baseUrl(baseUrl)
                        .apiKey(apiKey())
                        .modelName(modelName)
                        .temperature(0.3)
                        .build())
                // 流式版与非流式同参数，否则同一个 Agent 走两条路径会有人格分裂
                .streamingChatModel(OpenAiStreamingChatModel.builder()
                        .baseUrl(baseUrl)
                        .apiKey(apiKey())
                        .modelName(modelName)
                        .temperature(0.3)
                        .build())
                .modelName(modelName)
                .build();
    }

    private static String apiKey() {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "缺少环境变量 DEEPSEEK_API_KEY, 无法初始化 DeepSeek 模型。"
                            + "请先 source dev.env 或设置用户级环境变量。");
        }
        return apiKey;
    }
}
