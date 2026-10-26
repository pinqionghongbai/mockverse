package com.mockverse.interview.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.google.adk.agents.LlmAgent;
import com.google.adk.runner.Runner;
import com.mockverse.interview.agent.AgentRuntime;
import com.mockverse.interview.agent.AgentRuntime.ChatResult;
import com.mockverse.interview.agent.InterviewerPrompts;
import com.mockverse.interview.agent.MultiAgentFactory;
import com.mockverse.interview.cache.QuestionCache;
import com.mockverse.interview.cache.RateLimitExceededException;
import com.mockverse.interview.cache.RedisFeatureProperties;
import com.mockverse.interview.cache.SessionLock;
import com.mockverse.interview.cache.SseRateLimiter;
import com.mockverse.interview.config.InterviewProperties;
import com.mockverse.interview.dto.AnswerResponse;
import com.mockverse.interview.dto.MessageView;
import com.mockverse.interview.dto.StartResponse;
import com.mockverse.interview.entity.ChatMessage;
import com.mockverse.interview.entity.InterviewSession;
import com.mockverse.interview.entity.Question;
import com.mockverse.interview.mapper.ChatMessageMapper;
import com.mockverse.interview.mapper.InterviewSessionMapper;
import com.mockverse.interview.mapper.QuestionMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 面试主流程: 开会话 → 出题 → 收回答 → 追问。
 *
 * <p><b>职责边界</b>: 抽题和落库由本服务负责（确定性、可测试），LLM 只负责"怎么问"。
 * 这样即使模型跑偏，题库与会话数据也不会脏。Agent 侧的 {@code pick_question} 工具
 * 只用于面试中途"换一题"。
 *
 * <p><b>事务边界</b>: 刻意不用方法级 {@code @Transactional}。一次 DeepSeek 往返是秒级的，
 * 若把它圈进事务，连接会被独占 2-4 秒，并发面试一多就耗尽 Hikari 池。这里用
 * {@link TransactionTemplate} 只包住"纯 DB 写"的那几行，LLM 调用在事务之外。
 *
 * <p><b>D12 起的 Agent 链路</b>: 会话由 {@code root_coordinator} 编排树接管——
 * 面试消息路由给 interviewer_agent，中途问知识/贴简历也能转到对应子 Agent。
 * 树只建一次（子 Agent 在同一 invocation 内接力，无需各自 Runner/Session），
 * 每条回复按 {@code Event.author()} 分条落库，agent_name 列终于名副其实。
 * 子 Agent 超时/失败时返回降级话术，主流程不崩（三层兜底见 {@link #chat}）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewService {

    /** 兜底话术: 子 Agent 挂了也说得出话，用户至少知道"可以继续面试"。 */
    private static final String CAPABILITY_UNAVAILABLE =
            "（该能力暂时不可用，请稍后重试或换个问题；面试流程不受影响，可继续作答。）";

    /** 降级消息的作者名。与子 Agent 名区分开，历史记录里一眼能看出这不是正常回复。 */
    private static final String FALLBACK_AUTHOR = "system";

    /** 整轮 invocation（路由 + 转移 + 子 Agent + 工具）的超时上限。 */
    private static final Duration AGENT_TIMEOUT = Duration.ofSeconds(120);

    private final InterviewSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final QuestionMapper questionMapper;
    private final MultiAgentFactory multiAgentFactory;
    private final AgentRuntime runtime;
    private final InterviewProperties properties;
    private final TransactionTemplate tx;
    private final QuestionCache questionCache;
    private final SessionLock sessionLock;
    private final SseRateLimiter rateLimiter;
    private final RedisFeatureProperties redisProps;

    /** 编排树只建一次并复用: 树和 Runner 都是轻量对象，模型才是重资源（单例 bean）。 */
    private LlmAgent root;
    private Runner rootRunner;

    @PostConstruct
    void init() {
        root = multiAgentFactory.create();
        rootRunner = runtime.runnerFor(root);
        log.info("多 Agent 编排树就绪: root={}, 子Agent={}",
                root.name(), root.subAgents().stream().map(a -> a.name()).toList());
    }

    public StartResponse start(Long userId, Long questionId, String category, String difficulty) {
        Question question = questionId == null
                ? pickQuestion(category, difficulty)
                : pickQuestionById(questionId);

        // 先预生成会话 ID: adkSessionId 依赖它，而 LLM 调用必须在事务之外
        long sessionId = IdWorker.getId();
        String adkSessionId = "iv-" + sessionId;
        runtime.ensureSession(String.valueOf(userId), adkSessionId);

        ChatResult reply = chat(userId, adkSessionId,
                InterviewerPrompts.opening(question.getContent(), question.getReferenceAnswer()));

        tx.executeWithoutResult(status -> {
            InterviewSession session = new InterviewSession();
            session.setId(sessionId);
            session.setUserId(userId);
            session.setTitle("模拟面试 · " + question.getCategory());
            session.setStatus("IN_PROGRESS");
            session.setInterviewType("JAVA_BACKEND");
            session.setTotalRounds(0);
            session.setMaxRounds(properties.getMaxRounds());
            session.setStartedAt(LocalDateTime.now());
            sessionMapper.insert(session);
            appendTurns(sessionId, reply, question.getId());
        });

        return new StartResponse(
                sessionId, adkSessionId, question.getId(),
                question.getCategory(), question.getDifficulty(),
                question.getContent(), replyText(reply));
    }

    public AnswerResponse answer(Long sessionId, String userAnswer) {
        if (userAnswer == null || userAnswer.isBlank()) {
            // content 列是 NOT NULL，空回答直接放行会变成 DataIntegrityViolation（500），提前拦成 400
            throw new IllegalArgumentException("回答不能为空");
        }
        // D23: 会话级分布式锁。轮数计算、历史回放、落库必须在同一临界区内——
        // 否则并发请求会各自算出同一个 round、各自 rebuildSession 互相覆盖。
        // 锁在 LLM 调用之外才加是没用的（临界区正是"读状态→算轮数→写结果"整段）。
        return sessionLock.withLock(sessionId, () -> answerLocked(sessionId, userAnswer));
    }

    /** {@link #answer} 的临界区部分：调用方已持会话锁。 */
    private AnswerResponse answerLocked(Long sessionId, String userAnswer) {
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在: " + sessionId);
        }
        if (!"IN_PROGRESS".equals(session.getStatus())) {
            throw new IllegalStateException("会话已结束: " + session.getStatus());
        }

        int round = (session.getTotalRounds() == null ? 0 : session.getTotalRounds()) + 1;
        boolean finished = round >= session.getMaxRounds();

        String adkSessionId = "iv-" + sessionId;
        // 每轮重建（D13）: chat_message 是唯一事实源, ADK session 是本轮工作缓存。
        // 重启恢复(session 丢了)与流式污染(session 脏了)在同一条路径上被消灭
        int restored = runtime.rebuildSession(String.valueOf(session.getUserId()), adkSessionId,
                replayHistory(sessionId));
        if (restored > 0) {
            log.info("会话重建: {} 条历史消息已回放灌回 ADK (session={})", restored, adkSessionId);
        }

        // 先拿模型回复再落库: 模型失败时用户消息不会半截入库，重试也不会产生重复回答
        ChatResult reply = chat(session.getUserId(), adkSessionId,
                InterviewerPrompts.answerTurn(userAnswer, finished));

        tx.executeWithoutResult(status -> {
            appendMessage(sessionId, "USER", null, userAnswer, null);
            appendTurns(sessionId, reply, null);
            session.setTotalRounds(round);
            if (finished) {
                session.setStatus("COMPLETED");
                session.setEndedAt(LocalDateTime.now());
            }
            sessionMapper.updateById(session);
        });

        return new AnswerResponse(sessionId, round, replyText(reply), finished);
    }

    public List<MessageView> history(Long sessionId) {
        return messageMapper.selectList(
                        Wrappers.<ChatMessage>lambdaQuery()
                                .eq(ChatMessage::getSessionId, sessionId)
                                .orderByAsc(ChatMessage::getSeqNo))
                .stream()
                .map(m -> new MessageView(m.getSeqNo(), m.getRole(), m.getAgentName(),
                        m.getContent(), m.getCreatedAt()))
                .toList();
    }

    // ------------------------------------------------------------------
    // SSE 流式（D13 下午）
    // ------------------------------------------------------------------

    /** 流式端点的事件载荷: chunk=增量文本, tool=正在调用工具, done=整轮结束, error=降级。 */
    public sealed interface StreamEvent permits ChunkEvent, ToolEvent, DoneEvent, ErrorEvent {}

    public record ChunkEvent(String author, String delta) implements StreamEvent {}

    /**
     * 工具调用信号（D19）：{@code judge_code} 要 0.6~10s，这段时间客户端只看到空白，
     * 会以为卡死。前端收到就显示"正在判题…"。
     *
     * <p><b>撤销时机</b>（三查补）：收到**任意后续事件**（chunk / done / error）或流关闭
     * 时撤掉——不能只等第一段 chunk。判题超时或工具失败时不会再有文字，只发一个
     * {@link ErrorEvent}，按"等 chunk"写会让提示一直挂着。
     *
     * <p>只报**工具名**，不报参数——参数里是候选人完整代码，没必要重复推一遍。
     * 消费端遇到不认识的事件类型应忽略而不是报错（新增事件类型属于契约扩展）。
     */
    public record ToolEvent(String author, String tool) implements StreamEvent {}

    public record DoneEvent(int round, boolean finished) implements StreamEvent {}

    public record ErrorEvent(String message) implements StreamEvent {}

    /**
     * 流式回答一轮（D13 SSE）: 返回 Flowable 由 Controller 订阅转 SseEmitter。
     *
     * <p>校验与 {@link #answer} 一致且同步执行——400 类错误在订阅开始前就抛,
     * HTTP 层还能走正常的 ProblemDetail 处理, 而不是流开了一半才失败。
     *
     * <p>落库时机在流终止后: 正常结束聚合 turns 分条落库; 失败时用户已看到部分文本,
     * 把「USER + 已收到的部分 + 降级话术」一并落库——历史必须与用户看到的一致,
     * 这是流式比同步多出来的一致性责任。轮数照进、状态照转, 降级不改主流程。
     */
    public io.reactivex.rxjava3.core.Flowable<StreamEvent> answerStream(Long sessionId, String userAnswer) {
        if (userAnswer == null || userAnswer.isBlank()) {
            throw new IllegalArgumentException("回答不能为空");
        }
        // D23: 锁必须活到整条流结束——方法级 synchronized 在返回 Flowable 时就释放了，
        // 那时真正的工作还没开始（D13 四查的原话："单方法锁形同虚设"）。
        String lockToken = sessionLock.tryAcquire(sessionId);
        try {
            return answerStreamLocked(sessionId, userAnswer)
                    // doFinally 在 onComplete / onError / dispose 三种终止上都会执行。
                    // 但**实际生效的是前两种**：InterviewController 在客户端断开时只记日志、
                    // 不 dispose 订阅（刻意的，为了让 defer 里的落库照常跑完），所以"断开"
                    // 最终仍以 onComplete 收尾。dispose 分支是防御性的，不是主路径。
                    // D23 实测：curl --max-time 1 断开后，锁在 t+1s 仍持有、t+2s 已释放。
                    .doFinally(() -> sessionLock.release(sessionId, lockToken));
        } catch (RuntimeException e) {
            // 订阅前抛错（会话不存在 400 / 已结束 409 / 回放失败）时流还没建立，
            // doFinally 不会执行，必须在这里释放，否则这把锁要挂满 TTL
            sessionLock.release(sessionId, lockToken);
            throw e;
        }
    }

    /** {@link #answerStream} 的临界区部分：调用方已持会话锁。 */
    private io.reactivex.rxjava3.core.Flowable<StreamEvent> answerStreamLocked(Long sessionId, String userAnswer) {
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在: " + sessionId);
        }
        if (!"IN_PROGRESS".equals(session.getStatus())) {
            throw new IllegalStateException("会话已结束: " + session.getStatus());
        }
        // D23: 限流放在会话校验之后、重建会话之前——被拒的请求不该付任何 Agent 成本。
        // 按 userId 而不是 sessionId 计数：换个会话重新开一场绕不过去。
        if (!rateLimiter.tryAcquire(session.getUserId())) {
            throw new RateLimitExceededException(
                    "请求过于频繁，请稍后再试（每分钟上限 " + rateLimitPerMinute() + " 次）");
        }
        int round = (session.getTotalRounds() == null ? 0 : session.getTotalRounds()) + 1;
        boolean finished = round >= session.getMaxRounds();

        String adkSessionId = "iv-" + sessionId;
        String userId = String.valueOf(session.getUserId());
        int restored = runtime.rebuildSession(userId, adkSessionId, replayHistory(sessionId));
        if (restored > 0) {
            log.info("会话重建: {} 条历史消息已回放灌回 ADK (session={})", restored, adkSessionId);
        }

        List<AgentRuntime.Turn> collected = new java.util.concurrent.CopyOnWriteArrayList<>();
        StringBuilder current = new StringBuilder();
        java.util.concurrent.atomic.AtomicReference<String> currentAuthor = new java.util.concurrent.atomic.AtomicReference<>();

        return runtime.converseStream(rootRunner, userId, adkSessionId,
                        InterviewerPrompts.answerTurn(userAnswer, finished), AGENT_TIMEOUT)
                .map(chunk -> chunk.isToolCall()
                        ? (StreamEvent) new ToolEvent(chunk.author(), chunk.toolCall())
                        : (StreamEvent) new ChunkEvent(chunk.author(), chunk.text()))
                .doOnNext(evt -> {
                    // 只聚合文本: 工具信号若参与聚合, 会在 turn 边界上插入空片段
                    if (evt instanceof ChunkEvent c) {
                        String prev = currentAuthor.get();
                        if (prev != null && !prev.equals(c.author())) {
                            collected.add(new AgentRuntime.Turn(prev, current.toString()));
                            current.setLength(0);
                        }
                        currentAuthor.set(c.author());
                        current.append(c.delta());
                    }
                })
                .doOnComplete(() -> {
                    String author = currentAuthor.get();
                    if (author != null && !current.isEmpty()) {
                        collected.add(new AgentRuntime.Turn(author, current.toString()));
                    }
                    // 二查修复: 收尾后清空聚合状态。否则 defer 落库失败转进 onError 时,
                    // saved = collected + partial 会把最后一个 turn 重复计入(瞬时 DB 故障
                    // 恢复后真会落库两条相同消息)
                    current.setLength(0);
                    currentAuthor.set(null);
                })
                .concatWith(io.reactivex.rxjava3.core.Flowable.defer(() -> {
                    // 流正常结束: 落库 + 发 Done
                    tx.executeWithoutResult(status -> {
                        appendMessage(sessionId, "USER", null, userAnswer, null);
                        appendTurns(sessionId, new ChatResult(List.copyOf(collected), null), null);
                        session.setTotalRounds(round);
                        if (finished) {
                            session.setStatus("COMPLETED");
                            session.setEndedAt(LocalDateTime.now());
                        }
                        sessionMapper.updateById(session);
                    });
                    return io.reactivex.rxjava3.core.Flowable.just(new DoneEvent(round, finished));
                }))
                .onErrorResumeNext(e -> {
                    // 流失败: 已推给用户的文本不能从历史里消失, 降级收尾后发 Error
                    log.warn("流式链路失败, 降级收尾: {}", e.getMessage());
                    List<AgentRuntime.Turn> saved = new java.util.ArrayList<>(collected);
                    String partial = current.toString();
                    if (currentAuthor.get() != null && !partial.isBlank()) {
                        saved.add(new AgentRuntime.Turn(currentAuthor.get(), partial));
                    }
                    tx.executeWithoutResult(status -> {
                        appendMessage(sessionId, "USER", null, userAnswer, null);
                        appendTurns(sessionId, new ChatResult(saved, "stream-failed"), null);
                        // 四查修复: saved 为空时 appendTurns 的空兜底已落一条降级话术,
                        // 显式这条只在"有部分文本"时追加——否则空流失败会在库里落两条
                        // 相同的 CAPABILITY_UNAVAILABLE
                        if (!saved.isEmpty()) {
                            appendMessage(sessionId, "AGENT", FALLBACK_AUTHOR, CAPABILITY_UNAVAILABLE, null);
                        }
                        session.setTotalRounds(round);
                        if (finished) {
                            session.setStatus("COMPLETED");
                            session.setEndedAt(LocalDateTime.now());
                        }
                        sessionMapper.updateById(session);
                    });
                    return io.reactivex.rxjava3.core.Flowable.just(
                            (StreamEvent) new ErrorEvent("该能力暂时不可用，面试流程不受影响，可继续作答。"));
                });
    }

    // ------------------------------------------------------------------

    /**
     * 抽题。先按条件取候选集再在内存里随机，避免 {@code ORDER BY RAND()} 全表扫描；
     * 条件列顺序与 {@code idx_cat_diff_enabled} 一致，能命中联合索引。
     *
     * <p>D23 起候选集走 Redis 缓存（cache-aside），Redis 不可用时自动回源 MySQL——
     * 缓存是加速器不是依赖，见 {@link QuestionCache}。
     */
    private Question pickQuestion(String category, String difficulty) {
        List<Question> candidates = questionCache.candidates(category, difficulty,
                () -> questionMapper.selectList(
                        Wrappers.<Question>lambdaQuery()
                                .eq(category != null && !category.isBlank(), Question::getCategory, category)
                                .eq(difficulty != null && !difficulty.isBlank(), Question::getDifficulty, difficulty)
                                .eq(Question::getEnabled, true)));
        if (candidates.isEmpty()) {
            throw new IllegalStateException("题库为空或没有符合条件的题目，请先导入题库。");
        }
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    /** 指定题目开面（"重做此题"/冒烟固定题目用）。 */
    private Question pickQuestionById(Long questionId) {
        Question question = questionMapper.selectById(questionId);
        if (question == null || !Boolean.TRUE.equals(question.getEnabled())) {
            throw new IllegalArgumentException("题目不存在或已停用: " + questionId);
        }
        return question;
    }

    /**
     * 跑一轮多 Agent 对话，带三层兜底（超时 / 异常 / 错误事件——前两层在
     * {@link AgentRuntime#converse} 里捕获，这里做最终裁决）。
     *
     * <p>调用方对返回值的使用约定: {@code turns()} 非空按 author 分条落库，
     * 为空则落一条 {@link #CAPABILITY_UNAVAILABLE}——降级不改变主流程（轮数照进、
     * 会话状态照转），用户明确知道"面试还能继续"。
     */
    private ChatResult chat(Long userId, String adkSessionId, String userText) {
        long begin = System.currentTimeMillis();
        ChatResult result = runtime.converse(rootRunner, String.valueOf(userId), adkSessionId,
                userText, AGENT_TIMEOUT);
        log.info("Agent 回复完成, 耗时 {} ms, turns={}, error={}",
                System.currentTimeMillis() - begin,
                result.turns().stream()
                        .map(t -> t.author() + "(" + t.text().length() + "字)").toList(),
                result.error());

        if (result.fullText().isBlank()) {
            // 覆盖两种形态: result.error() 非空（超时/异常/错误事件），或事件流压根没有文本。
            // 都按"子 Agent 失败"降级——用户拿到明确话术，会话不崩，面试可继续
            log.warn("Agent 链路无有效输出, 返回降级话术: {}", result.error());
        }
        return result;
    }

    /** agent_name 列终于名副其实: 每条 turn 的 author 就是说这句话的 Agent。 */
    private void appendTurns(Long sessionId, ChatResult result, Long questionId) {
        if (result.turns().isEmpty()) {
            appendMessage(sessionId, "AGENT", FALLBACK_AUTHOR, CAPABILITY_UNAVAILABLE, questionId);
            return;
        }
        for (AgentRuntime.Turn t : result.turns()) {
            appendMessage(sessionId, "AGENT", t.author(), t.text(), questionId);
        }
    }

    /** 对外（HTTP 响应）用拼接全文; 链路失败时给降级话术。 */
    private static String replyText(ChatResult result) {
        return result.fullText().isBlank() ? CAPABILITY_UNAVAILABLE : result.fullText();
    }

    private void appendMessage(Long sessionId, String role, String agentName, String content, Long questionId) {
        ChatMessage msg = new ChatMessage();
        msg.setSessionId(sessionId);
        msg.setRole(role);
        msg.setAgentName(agentName);
        msg.setContent(content);
        msg.setQuestionId(questionId);
        msg.setSeqNo(nextSeq(sessionId));
        msg.setTokenCount(content == null ? 0 : content.length());
        messageMapper.insert(msg);
    }

    /** 会话内序号从 1 递增；由 uk_session_seq 唯一索引兜底防并发重复。 */
    private int nextSeq(Long sessionId) {
        ChatMessage last = messageMapper.selectOne(
                Wrappers.<ChatMessage>lambdaQuery()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByDesc(ChatMessage::getSeqNo)
                        .last("LIMIT 1"));
        return last == null ? 1 : last.getSeqNo() + 1;
    }

    /** 限流上限，只用于给用户看的提示文案（真正的判断在 {@link SseRateLimiter} 里）。 */
    private int rateLimitPerMinute() {
        return redisProps.getSsePerMinute();
    }

    /** 恢复素材: 该会话全部消息按 seq 升序投影（含 USER 与 AGENT 两类）。 */
    private List<AgentRuntime.RestorableMessage> replayHistory(Long sessionId) {
        return messageMapper.selectList(
                        Wrappers.<ChatMessage>lambdaQuery()
                                .eq(ChatMessage::getSessionId, sessionId)
                                .orderByAsc(ChatMessage::getSeqNo))
                .stream()
                .map(m -> new AgentRuntime.RestorableMessage(m.getRole(), m.getAgentName(), m.getContent()))
                .toList();
    }
}
