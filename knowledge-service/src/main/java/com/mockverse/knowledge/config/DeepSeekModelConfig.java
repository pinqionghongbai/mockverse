package com.mockverse.knowledge.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
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
 * （D16 知识 Agent 化时若需要 ADK 适配, 再按单体 DeepSeekModelConfig 的 LangChain4j
 * builder 做法接 google-adk-langchain4j——本服务当前没有这个依赖。）
 *
 * <p>API Key 只从环境变量读取, 不接受配置文件注入 (章程安全红线)。
 */
@Configuration
public class DeepSeekModelConfig {

    @Bean
    public ChatModel ragChatModel(
            @Value("${mockverse.deepseek.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${mockverse.deepseek.model:deepseek-chat}") String modelName) {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "缺少环境变量 DEEPSEEK_API_KEY, 无法初始化 DeepSeek 模型。"
                            + "请先 source dev.env 或设置用户级环境变量。");
        }
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.2)
                .build();
    }
}
