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
import java.util.UUID;
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
     * 会话恢复（D13）：把 DB 里的历史消息回放灌回 ADK 会话（重启后 InMemory 会话丢失的还债）。
     *
     * <p>选型走"回放式"而不是自实现 DB 版 SessionService: {@code chat_message} 表本就存着
     * 完整有序历史（seq + author 齐全），灌回 {@code appendEvent} 就能重建 LLM 上下文——
     * 验收标准只要求"重启后上下文还在"，这是满足它的最短路径。
     *
     * <p><b>幂等</b>: ADK 会话已有事件（进程没重启过）就跳过，不重复灌。
     * 判据是事件数 &gt; 0——正常会话每轮至少产生一条 user 事件。
     *
     * @param history 按 seq 升序的历史消息; user 消息 author 固定为 "user"，
     *                agent 消息用落库的 agent_name（保持归因一致）
     */
    public int restoreSession(String userId, String adkSessionId, List<RestorableMessage> history) {
        var session = sessionService
                .getSession(properties.getAppName(), userId, adkSessionId, Optional.empty())
                .blockingGet();
        if (session == null) {
            throw new IllegalStateException("会话尚未创建，先 ensureSession: " + adkSessionId);
        }
        if (!session.events().isEmpty() || history == null || history.isEmpty()) {
            return 0;
        }
        for (RestorableMessage m : history) {
            String author = "USER".equals(m.role()) ? "user" : m.agentName();
            Content content = Content.builder()
                    .role("USER".equals(m.role()) ? "user" : "model")
                    .parts(List.of(Part.fromText(m.content())))
                    .build();
            Event event = Event.builder()
                    .id(UUID.randomUUID().toString())
                    .author(author)
                    .timestamp(System.currentTimeMillis())
                    .content(content)
                    .build();
            sessionService.appendEvent(session, event).blockingGet();
        }
        return history.size();
    }

    /**
     * 每轮重建会话（D13 修复 session 污染后的最终形态）。
     *
     * <p><b>为什么每轮删了重建</b>: SSE 流式轮会把 chunk 粒度事件（含流结束时的空
     * content 事件）append 进 session, 下一轮组装 LlmRequest 时空文本事件转 AiMessage
     * 会抛 {@code text cannot be null or blank}（D13 实测炸过一轮）。与其逐事件甄别,
     * 不如确立更简单的模型: <b>chat_message 是唯一事实源, ADK session 只是本轮的
     * 工作缓存</b>——每轮开始删缓存、回放干净的 turn 级历史。重启恢复（session 丢了）
     * 和缓存污染（session 脏了）在同一条路径上被消灭。
     *
     * <p>代价: 每轮一次 DB 查询 + N 次 appendEvent, 毫秒级, 面试 5 轮可忽略。
     */
    public int rebuildSession(String userId, String adkSessionId, List<RestorableMessage> history) {
        sessionService.deleteSession(properties.getAppName(), userId, adkSessionId)
                .onErrorComplete() // 不存在视为已删
                .blockingAwait();
        ensureSession(userId, adkSessionId);
        return restoreSession(userId, adkSessionId, history);
    }

    /** 回放素材: chat_message 行的最小投影（避免 agent 包反向依赖 entity/mapper）。 */
    public record RestorableMessage(String role, String agentName, String content) {}

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
     * 流式回复的增量片段（D13 SSE）：author + 一小段文字（通常几个字到一个词）。
     *
     * <p>与 {@link Turn} 的区别: text 是**增量**不是全量, 消费端负责拼接。
     */
    public record Chunk(String author, String text) {}

    /**
     * 流式跑一轮对话（D13 SSE）: 返回 Flowable 供 Controller 直接订阅,
     * 每个含文本的事件映射为一个 {@link Chunk}——终端逐字输出的数据源。
     *
     * <p>与 {@link #converse} 的三点差异:
     * <ul>
     *   <li>{@code RunConfig.streamingMode=SSE}: LangChain4j 适配器由此切到
     *       streamingChatModel, 模型逐段回调而非整段返回</li>
     *   <li>非阻塞: 不用 blockingIterable, 直接把 Flowable 交给调用方订阅</li>
     *   <li>超时语义变为"事件间超时": 流式下逐字输出, 事件间隔超时(120s 没有新片段)
     *       即视为卡死——比整轮 deadline 更贴合流的形态</li>
     * </ul>
     *
     * <p>兜底注意: 错误以 onError 抛给订阅者（订阅者负责转降级事件）;
     * functionCall/functionResponse 事件被过滤, 不会混进文本流。
     */
    public io.reactivex.rxjava3.core.Flowable<Chunk> converseStream(Runner runner, String userId,
                                                                    String adkSessionId, String userText,
                                                                    Duration eventIntervalTimeout) {
        Content message = Content.builder()
                .role("user")
                .parts(List.of(Part.fromText(userText)))
                .build();

        return runner.runAsync(userId, adkSessionId, message,
                        RunConfig.builder().setStreamingMode(RunConfig.StreamingMode.SSE).build())
                .timeout(eventIntervalTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .concatMapIterable(e -> {
                    List<Chunk> out = new ArrayList<>();
                    e.content().ifPresent(content ->
                            content.parts().ifPresent(parts -> parts.forEach(p ->
                                    p.text().ifPresent(t -> {
                                        if (!t.isBlank()) {
                                            out.add(new Chunk(e.author() == null ? "unknown" : e.author(), t));
                                        }
                                    }))));
                    return out;
                });
    }

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
