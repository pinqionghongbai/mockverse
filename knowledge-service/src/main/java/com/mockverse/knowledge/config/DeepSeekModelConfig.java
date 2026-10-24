package com.mockverse.knowledge.config;

import com.google.adk.models.langchain4j.LangChain4j;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * DeepSeek 模型 Bean（knowledge-service 副本，D15 机械拆分）。
 *
 * <p>走 [OI] 兼容协议, 由 langchain4j 直连 DeepSeek。本服务只需要 RAG 问答一个温度档：
 *
 * <p><b>必须低温</b>。RAG 的任务是"照着资料说"，不是"自由发挥"——temperature 高时模型
 * 容易把检索到的片段改写成自己记忆里的另一套说法，引用就对不上了。0.2 让输出贴着原文走。
 *
 * <p><b>D16 起有两个 bean，同一个温度档的两种形态</b>：
 * <ul>
 *   <li>{@link #ragChatModel} — 裸 langchain4j {@code ChatModel}，给 D10 的
 *       {@code RagAnswerService}（代码拼上下文）用</li>
 *   <li>{@link #knowledgeModel} — 同一份 0.2 参数，包成 ADK 的 {@code LangChain4j}，
 *       给 A2A 暴露的 {@code knowledge_agent}（Agentic RAG，模型自己决定查不查）用</li>
 * </ul>
 * 两条路线并存是 D10/D12 的既定设计：参数必须一致，否则同一个知识库会有两种"人格"。
 *
 * <p>API Key 只从环境变量读取, 不接受配置文件注入 (章程安全红线)。
 */
@Configuration
public class DeepSeekModelConfig {

    @Bean
    public ChatModel ragChatModel(
            @Value("${mockverse.deepseek.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${mockverse.deepseek.model:deepseek-chat}") String modelName) {
        return build(baseUrl, modelName, 0.2);
    }

    /**
     * ADK 适配层：{@code knowledge_agent} 用的模型。
     *
     * <p>必须同时挂 {@code streamingChatModel}：A2A 的 {@code message/stream} 走
     * {@code RunConfig.streamingMode=SSE}，LangChain4j 适配器只认流式模型，
     * 没挂就直接报 "StreamingChatModel is not configured"（D13 踩过）。
     */
    @Bean
    public LangChain4j knowledgeModel(
            @Value("${mockverse.deepseek.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${mockverse.deepseek.model:deepseek-chat}") String modelName) {
        return LangChain4j.builder()
                .chatModel(build(baseUrl, modelName, 0.2))
                // 流式版与非流式同参数，否则同一个 Agent 走两条路径会有人格分裂
                .streamingChatModel(buildStreaming(baseUrl, modelName, 0.2))
                .modelName(modelName)
                .build();
    }

    private static ChatModel build(String baseUrl, String modelName, double temperature) {
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey())
                .modelName(modelName)
                .temperature(temperature);
        return builder.build();
    }

    /** 流式版构建: 参数口径与 build() 完全一致, 只是换成 StreamingChatModel。 */
    private static StreamingChatModel buildStreaming(String baseUrl, String modelName, double temperature) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey())
                .modelName(modelName)
                .temperature(temperature)
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
