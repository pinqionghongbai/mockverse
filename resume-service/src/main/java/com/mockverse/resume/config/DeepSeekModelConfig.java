package com.mockverse.resume.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * DeepSeek 模型 Bean（resume-service 副本，D15 机械拆分）。
 *
 * <p>走 [OI] 兼容协议, 由 langchain4j 直连 DeepSeek。本服务只需要简历诊断一个模型：
 *
 * <p>**强制 JSON 输出 + temperature 0.3**。{@code responseFormat("json_object")} 是
 * 供应商侧的约束，比"提示词里求模型输出 JSON"可靠得多——后者在长输出时经常带一段
 * "好的，以下是诊断结果："。0.3：评分要稳，但不能低到每次都给一样的模板话术。
 *
 * <p>注意：这个开关打开后，**提示词里必须出现 "json" 字样**，否则 DeepSeek 直接返回 400
 * （实测报错 {@code Prompt must contain the word 'json' in some form}）。
 *
 * <p>API Key 只从环境变量读取, 不接受配置文件注入 (章程安全红线)。
 */
@Configuration
public class DeepSeekModelConfig {

    @Bean
    public ChatModel diagnosisChatModel(
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
                .temperature(0.3)
                .responseFormat("json_object")
                .build();
    }
}
