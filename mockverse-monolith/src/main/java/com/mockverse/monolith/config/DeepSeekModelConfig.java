package com.mockverse.monolith.config;

import com.google.adk.models.langchain4j.LangChain4j;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * DeepSeek 模型 Bean。
 *
 * <p>与 D3/D5 的做法一致: 走 [OI] 兼容协议 ([OI] 兼容端点), 由 google-adk-langchain4j
 * 把 langchain4j 的 ChatModel 适配成 ADK 的 Model 接口 —— 适配器模式。
 *
 * <p>API Key 只从环境变量读取, 不接受配置文件注入 (章程安全红线)。
 */
@Configuration
public class DeepSeekModelConfig {

    /**
     * ADK 用的模型：面试官需要一点表达上的发挥，temperature 0.7。
     *
     * <p>D13 起同时挂 streamingChatModel: SSE 流式路径（RunConfig.streamingMode=SSE）下
     * LangChain4j 适配器只认 streamingChatModel, 没有它流式请求直接报错
     * "StreamingChatModel is not configured"。
     */
    @Bean
    public LangChain4j deepSeekModel(
            @Value("${mockverse.deepseek.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${mockverse.deepseek.model:deepseek-chat}") String modelName) {

        return LangChain4j.builder()
                .chatModel(build(baseUrl, modelName, 0.7, false))
                // 流式版必须与非流式同参数, 否则同一个 Agent 走两条路径会有人格分裂
                .streamingChatModel(buildStreaming(baseUrl, modelName, 0.7, false))
                .modelName(modelName)
                .build();
    }

    /**
     * RAG 问答用的模型：**必须低温**。
     *
     * <p>RAG 的任务是"照着资料说"，不是"自由发挥"——temperature 0.7 时模型容易把检索到的
     * 片段改写成自己记忆里的另一套说法，引用就对不上了。0.2 让输出贴着原文走。
     */
    @Bean
    public ChatModel ragChatModel(
            @Value("${mockverse.deepseek.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${mockverse.deepseek.model:deepseek-chat}") String modelName) {
        return build(baseUrl, modelName, 0.2, false);
    }

    /**
     * 简历诊断用的模型：**强制 JSON 输出**。
     *
     * <p>{@code responseFormat("json_object")} 是供应商侧的约束，比"提示词里求模型输出 JSON"
     * 可靠得多——后者在长输出时经常带一段"好的，以下是诊断结果："。temperature 0.3：
     * 评分要稳，但不能低到每次都给一样的模板话术。
     *
     * <p>注意：这个开关打开后，**提示词里必须出现 "json" 字样**，否则 DeepSeek 直接返回 400
     * （实测报错 {@code Prompt must contain the word 'json' in some form}）。
     */
    @Bean
    public ChatModel diagnosisChatModel(
            @Value("${mockverse.deepseek.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${mockverse.deepseek.model:deepseek-chat}") String modelName) {
        return build(baseUrl, modelName, 0.3, true);
    }

    private static ChatModel build(String baseUrl, String modelName, double temperature, boolean jsonMode) {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "缺少环境变量 DEEPSEEK_API_KEY, 无法初始化 DeepSeek 模型。"
                            + "请先 source dev.env 或设置用户级环境变量。");
        }
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(temperature);
        if (jsonMode) {
            builder.responseFormat("json_object");
        }
        return builder.build();
    }

    /** 流式版构建: 参数口径与 build() 完全一致, 只是换成 StreamingChatModel。 */
    private static StreamingChatModel buildStreaming(String baseUrl, String modelName,
                                                     double temperature, boolean jsonMode) {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "缺少环境变量 DEEPSEEK_API_KEY, 无法初始化 DeepSeek 模型。"
                            + "请先 source dev.env 或设置用户级环境变量。");
        }
        OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder = OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(temperature);
        if (jsonMode) {
            builder.responseFormat("json_object");
        }
        return builder.build();
    }
}
