package com.mockverse.interview.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.mockverse.interview.kafka.JudgeRequestMessage;
import com.mockverse.interview.kafka.JudgeRequestProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 异步判题的提交登记 + 结果推送（D20）。
 *
 * <p><b>它解决的是一段真实的等待</b>：D18/D19 的判题是同步的——{@code judge_code} 在
 * Agent 轮次里阻塞 0.6~10 秒，面试官的第一个字要等它返回。D20 把这段拆开：提交立刻
 * 拿到 {@code submissionId + PENDING}，判题在 Kafka 后面跑，结果再异步推回。
 *
 * <p><b>状态机只有三个状态</b>：{@code PENDING → COMPLETED} 或 {@code PENDING → FAILED}。
 * 不允许回到 PENDING——重复的结果消息第二次到达时直接丢弃，这就是结果侧的幂等。
 *
 * <p><b>存储是进程内的，边界写在这里</b>：提交状态与 SSE 订阅者都在内存里，重启即失；
 * 多实例部署时"提交在 A 实例、结果消费在 B 实例"会导致 A 找不到提交。D20 的验收是
 * 单实例下的"提交即返回 + 结果异步到达 + 重复消息不重复判题"，做到这里即可；
 * 要跨实例得把状态放进 Redis 或 DB——<b>这是已知限制，不是没意识到</b>。
 *
 * <p><b>为什么用 SseEmitter 而不是 Rx 的 Sink</b>：一个会话可能开多个页面（多标签、
 * 演示时开两个终端），SseEmitter 集合天然支持一对多，且与 D13 的
 * {@code InterviewController} 写法一致，不必再引入一套背压语义。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JudgeSubmissionService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    /** SSE 连接上限：判题结果通常几秒内到达，给 5 分钟足够覆盖排队。 */
    private static final long SSE_TIMEOUT_MILLIS = 300_000L;

    /** 内存里最多留多少个提交。超过就清掉已完成的旧记录，防止长跑把堆吃满。 */
    private static final int MAX_TRACKED = 500;

    private final JudgeRequestProducer producer;

    private final Map<String, Submission> submissions = new ConcurrentHashMap<>();
    private final Map<Long, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();

    /** 一个提交的当前状态。{@code result} 只在 {@code COMPLETED} 时非空。 */
    public record Submission(String submissionId, Long sessionId, String status,
                             JsonNode result, long submittedAt) {
    }

    /** 推给客户端的 SSE 事件载荷。 */
    public record JudgeEvent(String submissionId, String status, JsonNode result) {
    }

    /**
     * 登记一次提交并投递到 Kafka，立即返回（不等判题）。
     *
     * @throws IllegalArgumentException 代码为空——这是调用方的问题，抛出去变成 400
     */
    public Submission submit(Long sessionId, String code, String stdin,
                             String expectedOutput, Integer timeoutSeconds) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code 不能为空");
        }
        String submissionId = UUID.randomUUID().toString();
        Submission submission = new Submission(submissionId, sessionId, STATUS_PENDING, null,
                System.currentTimeMillis());
        submissions.put(submissionId, submission);

        CompletableFuture<SendResult<String, String>> future = producer.send(new JudgeRequestMessage(
                submissionId, code, stdin, expectedOutput, timeoutSeconds));
        if (future == null) {
            // 同步失败（序列化 / broker 不可达且已超过 max.block.ms）：提交本身仍然返回，
            // 但状态如实变成 FAILED，否则客户端会一直等一个永远不来的结果
            evictIfNeeded();
            return markFailed(submissionId);
        }
        // 异步失败（重试耗尽 / 分区不可用）只能从 Future 上看到——二查修正：
        // 之前只返回 boolean，这类失败会静默丢掉，提交永远停在 PENDING
        future.whenComplete((result, error) -> {
            if (error != null) {
                log.error("judge-request 异步发送失败: submissionId={}", submissionId, error);
                markFailed(submissionId);
            }
        });

        evictIfNeeded();
        return submission;
    }

    /**
     * 把提交标成 FAILED 并推送。**只在仍为 PENDING 时生效**——与结果侧同一个状态机，
     * 避免"结果已经到了、失败回调才来"把 COMPLETED 覆盖成 FAILED。
     */
    private Submission markFailed(String submissionId) {
        Submission current = submissions.get(submissionId);
        if (current == null || !STATUS_PENDING.equals(current.status())) {
            return current;
        }
        Submission failed = new Submission(submissionId, current.sessionId(), STATUS_FAILED,
                null, current.submittedAt());
        submissions.put(submissionId, failed);
        publish(failed);
        return failed;
    }

    /** 查询单个提交的当前状态（轮询兜底，SSE 连不上时用）。 */
    public Submission get(String submissionId) {
        return submissions.get(submissionId);
    }

    /**
     * 结果到达。**重复消息在这里被丢弃**（状态已经不是 PENDING），不会重复推给客户端。
     */
    public void complete(String submissionId, JsonNode result) {
        Submission current = submissions.get(submissionId);
        if (current == null) {
            log.warn("收到未知提交的判题结果，丢弃: submissionId={}（可能是别的实例处理的提交）",
                    submissionId);
            return;
        }
        if (!STATUS_PENDING.equals(current.status())) {
            log.info("judge-result 重复消息，跳过推送（幂等）: submissionId={}, 当前状态={}",
                    submissionId, current.status());
            return;
        }
        Submission done = new Submission(submissionId, current.sessionId(), STATUS_COMPLETED,
                result, current.submittedAt());
        submissions.put(submissionId, done);
        publish(done);
    }

    /** 打开一个会话的结果流。同一会话可开多条，结果会推给所有连接。 */
    public SseEmitter openStream(Long sessionId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        Set<SseEmitter> set = emitters.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet());
        set.add(emitter);
        emitter.onCompletion(() -> remove(sessionId, emitter));
        emitter.onTimeout(() -> remove(sessionId, emitter));
        emitter.onError(e -> remove(sessionId, emitter));
        log.info("判题结果流已连接: session={}, 当前连接数={}", sessionId, set.size());
        return emitter;
    }

    private void publish(Submission submission) {
        Set<SseEmitter> set = emitters.get(submission.sessionId());
        if (set == null || set.isEmpty()) {
            // 没人连着不算错误：客户端可以稍后用 GET 轮询拿结果
            log.info("提交 {} 状态变为 {}，但 session={} 没有打开的流，结果留在内存里等轮询",
                    submission.submissionId(), submission.status(), submission.sessionId());
            return;
        }
        JudgeEvent event = new JudgeEvent(submission.submissionId(), submission.status(),
                submission.result());
        for (SseEmitter emitter : set) {
            try {
                emitter.send(SseEmitter.event().data(event));
            } catch (Exception e) {
                // 客户端断开：移除这条连接，不影响其它订阅者，也不影响状态落库（这里没有落库）
                log.warn("判题结果推送失败，移除连接: session={}, {}", submission.sessionId(),
                        e.getMessage());
                remove(submission.sessionId(), emitter);
            }
        }
    }

    private void remove(Long sessionId, SseEmitter emitter) {
        Set<SseEmitter> set = emitters.get(sessionId);
        if (set != null) {
            set.remove(emitter);
        }
    }

    /**
     * 超过上限时清掉**已完成**的旧记录。
     *
     * <p>只清 COMPLETED/FAILED：PENDING 的提交可能还在判题，删了会导致结果回来时找不到。
     */
    private void evictIfNeeded() {
        if (submissions.size() <= MAX_TRACKED) {
            return;
        }
        int before = submissions.size();
        submissions.entrySet().removeIf(e -> !STATUS_PENDING.equals(e.getValue().status()));
        log.info("判题提交记录超过 {} 条，清理已完成记录: {} → {}", MAX_TRACKED, before,
                submissions.size());
    }
}
