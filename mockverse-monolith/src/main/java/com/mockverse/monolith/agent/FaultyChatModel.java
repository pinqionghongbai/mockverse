package com.mockverse.monolith.agent;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * 故障桩模型（D12 冒烟专用，正常链路永不使用）。
 *
 * <p>用途：人为"断掉"一个子 Agent，验证主流程兜底。两种故障模式：
 * <ul>
 *   <li><b>fail</b>：chat() 直接抛 RuntimeException，模拟模型服务不可用</li>
 *   <li><b>slow</b>：chat() 先睡眠再返回，模拟模型无限挂起（配合短超时触发 Reactive timeout）</li>
 * </ul>
 *
 * <p>只需 override {@code chat(ChatRequest)}：ADK 的 LangChain4j 适配器非流式路径
 * 调用的正是这个方法（LangChain4j.generateContent → chatModel().chat(chatRequest)）。
 * ChatModel 接口其余方法在 langchain4j 1.x 里全是 default，无需实现。
 */
public class FaultyChatModel implements ChatModel {

    private final long sleepMillis;
    private final String label;

    /** fail 模式：立即抛异常。 */
    public static FaultyChatModel fail(String label) {
        return new FaultyChatModel(label, 0);
    }

    /** slow 模式：睡眠 sleepMillis 毫秒（配合短超时用）。 */
    public static FaultyChatModel slow(String label, long sleepMillis) {
        return new FaultyChatModel(label, sleepMillis);
    }

    private FaultyChatModel(String label, long sleepMillis) {
        this.label = label;
        this.sleepMillis = sleepMillis;
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        if (sleepMillis > 0) {
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // 睡完照常抛——超时场景下根本走不到这里；走到这里说明超时没生效，也要暴露出来
            throw new IllegalStateException("[" + label + "] 桩模型睡眠后仍被调用，超时未生效");
        }
        throw new IllegalStateException("[" + label + "] 桩模型故障注入：模拟模型服务不可用");
    }

    @Override
    public String toString() {
        return "FaultyChatModel{label=" + label + ", sleepMillis=" + sleepMillis + "}";
    }
}
