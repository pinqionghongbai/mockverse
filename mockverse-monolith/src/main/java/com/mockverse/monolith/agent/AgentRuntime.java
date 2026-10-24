package com.mockverse.monolith.agent;

import com.google.adk.agents.RunConfig;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.common.collect.Lists;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.mockverse.monolith.config.InterviewProperties;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * ADK 运行时: 统一持有会话/产物/记忆三类服务, 并负责把事件流收敛成一段文本。
 *
 * <p><b>当前局限（D13 要还的债）</b>: 三类服务都是 InMemory 实现, 进程重启后会话丢失。
 * D13 的"聊天记录入库 + 会话恢复"会把 SessionService 换成 DB 实现, 或改为每轮用
 * chat_message 表重建上下文。
 */
@Component
public class AgentRuntime {

    private final InterviewProperties properties;

    @Getter
    private final InMemorySessionService sessionService = new InMemorySessionService();

    private final InMemoryArtifactService artifactService = new InMemoryArtifactService();
    private final InMemoryMemoryService memoryService = new InMemoryMemoryService();

    public AgentRuntime(InterviewProperties properties) {
        this.properties = properties;
    }

    /** 为某个 Agent 建 Runner（轻量对象，可重复创建）。 */
    public Runner runnerFor(com.google.adk.agents.BaseAgent agent) {
        return new Runner(agent, properties.getAppName(), artifactService, sessionService, memoryService);
    }

    /** 创建 ADK 会话；已存在则复用。 */
    public void ensureSession(String userId, String adkSessionId) {
        var existing = sessionService
                .getSession(properties.getAppName(), userId, adkSessionId, Optional.empty())
                .blockingGet();
        if (existing == null) {
            sessionService
                    .createSession(properties.getAppName(), userId, new ConcurrentHashMap<String, Object>(), adkSessionId)
                    .blockingGet();
        }
    }

    /**
     * 跑一轮对话并返回纯文本回复。
     *
     * <p>只收集 text 类型的 part —— 事件流里还有 functionCall / functionResponse,
     * 直接 {@code stringifyContent()} 会把工具报文也拼进回答里。
     *
     * <p>D12 起多 Agent 链路请改用 {@link #converse}（带 author 归因与错误检测）;
     * 本方法保留给 prompt-ab 冒烟（D9 的单 Agent A-B 对照）继续使用。
     */
    public String chat(Runner runner, String userId, String adkSessionId, String userText) {
        Content message = Content.builder()
                .role("user")
                .parts(List.of(Part.fromText(userText)))
                .build();

        List<Event> events = Lists.newArrayList(
                runner.runAsync(userId, adkSessionId, message, RunConfig.builder().build())
                        .blockingIterable());

        StringBuilder sb = new StringBuilder();
        for (Event e : events) {
            e.content().ifPresent(content ->
                    content.parts().ifPresent(parts ->
                            parts.forEach(p -> p.text().ifPresent(sb::append))));
        }
        return sb.toString().trim();
    }

    /**
     * 跑一轮对话并返回**按 author 归因**的结果（D12 多 Agent 版）。
     *
     * <p>单 Agent 时代把所有 text 拼起来就够了; 编排树里一次回复可能来自多个 Agent
     * （coordinator 转给谁、谁说了什么），必须靠 {@code Event.author()} 区分——
     * 拼成一坨就再也分不清哪句话是哪个 Agent 说的。
     *
     * <p>三层兜底的前两层在这里落地：
     * <ol>
     *   <li><b>超时</b>: Reactive {@code timeout()} 计时整个 invocation
     *       （含路由决策、转移、子 Agent 执行、工具调用），到点抛 RuntimeException 被捕获</li>
     *   <li><b>异常</b>: 模型/工具失败会沿事件流抛出（ADK 无回调时经
     *       switchIfEmpty(Single.error) 重抛），同样在这里捕获</li>
     *   <li><b>错误事件</b>: 并非所有失败都以异常形式出现，{@code Event.errorCode()/
     *       errorMessage()} 非空时标记 {@code result.error}——由调用方降级处理</li>
     * </ol>
     *
     * @param timeout 整轮 invocation 的超时上限
     */
    public ChatResult converse(Runner runner, String userId, String adkSessionId,
                               String userText, Duration timeout) {
        Content message = Content.builder()
                .role("user")
                .parts(List.of(Part.fromText(userText)))
                .build();

        List<Event> events;
        try {
            events = Lists.newArrayList(
                    runner.runAsync(userId, adkSessionId, message, RunConfig.builder().build())
                            .timeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                            .blockingIterable());
        } catch (RuntimeException e) {
            return new ChatResult(List.of(), "链路异常: " + rootMessage(e));
        }

        // 归因收集: 同一 author 的连续事件（多段 text）合并成一条 turn
        List<Turn> turns = new ArrayList<>();
        String currentAuthor = null;
        StringBuilder buf = new StringBuilder();
        String error = null;

        for (Event e : events) {
            if (e.errorMessage().isPresent() || e.errorCode().isPresent()) {
                // 先记录再继续: 错误事件后面可能还有正常文本（降级话术之外的有效输出）
                error = "事件错误: errorCode=" + e.errorCode().map(Object::toString).orElse("-")
                        + " errorMessage=" + e.errorMessage().orElse("-");
            }
            String text = extractText(e);
            if (text.isBlank()) {
                continue;
            }
            String author = e.author() == null ? "unknown" : e.author();
            if (author.equals(currentAuthor)) {
                buf.append(text);
            } else {
                if (currentAuthor != null) {
                    turns.add(new Turn(currentAuthor, buf.toString().trim()));
                }
                currentAuthor = author;
                buf = new StringBuilder(text);
            }
        }
        if (currentAuthor != null) {
            turns.add(new Turn(currentAuthor, buf.toString().trim()));
        }
        return new ChatResult(List.copyOf(turns), error);
    }

    private static String extractText(Event e) {
        StringBuilder sb = new StringBuilder();
        e.content().ifPresent(content ->
                content.parts().ifPresent(parts ->
                        parts.forEach(p -> p.text().ifPresent(sb::append))));
        return sb.toString();
    }

    /** 层层剥掉响应式包装，取最内层的业务异常消息（否则日志里全是 io.reactivex 帧）。 */
    private static String rootMessage(Throwable e) {
        Throwable t = e;
        for (int i = 0; i < 8 && t.getCause() != null && t.getCause() != t; i++) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    /** 一句话回复片段，author = 说这句话的 Agent 名。 */
    public record Turn(String author, String text) {}

    /**
     * 一轮对话的结构化结果。
     *
     * @param turns 按 author 归因的文本片段（有序）
     * @param error 非空表示链路失败（超时/异常/错误事件），此时 turns 可能只有部分或为空
     */
    public record ChatResult(List<Turn> turns, String error) {

        public boolean ok() {
            return error == null;
        }

        /** 全部 turn 的文本拼接（历史兼容：单 Agent 场景等价于旧 chat() 的返回）。 */
        public String fullText() {
            return turns.stream().map(Turn::text).reduce((a, b) -> a + "\n" + b).orElse("");
        }
    }
}
