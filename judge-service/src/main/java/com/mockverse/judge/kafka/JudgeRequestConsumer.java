package com.mockverse.judge.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mockverse.judge.judge.JavaJudge;
import com.mockverse.judge.judge.JudgeResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 消费 {@code judge-request}，调 {@link JavaJudge}，把结果发回 {@code judge-result}（D20）。
 *
 * <p><b>判题逻辑一行没改</b>：这里只是换了个触发方式——D18 是 Agent 通过 MCP 调
 * {@code judge_code}，D20 是消息队列投递。同一个 {@code JavaJudge} 实例，同样的
 * 编译/运行/超时/截断语义，所以两条链路的结果可以直接对照。
 *
 * <p><b>幂等（验收点：同一条消息重发 3 次，判题只真正执行 1 次）</b>：
 * 用 {@code submissionId} 做 {@link ConcurrentHashMap#add}，第一次返回 true 才判题；
 * 后续重复消息直接 ack 跳过。判题成功后再 ack，保证"消息丢了会重投、判过了不会重判"。
 *
 * <p><b>这个幂等是进程内的，有明确边界</b>：judge-service 重启后集合清空，同一条
 * 历史消息若被重放（比如换消费组、手工 seek）会再判一次。要做到跨重启幂等需要一张
 * 持久化表（submission_id 主键），D20 的验收只要求"重复消息不重复判题"，
 * 而 judge-service 的设计定位是无状态执行器，所以不引入 DB 依赖——
 * <b>这个取舍写在文档里，而不是让人误以为它是分布式幂等</b>。
 *
 * <p><b>失败时不 ack</b>：判题抛异常（而不是返回带 {@code error} 的结果）说明是
 * 环境问题（比如工作目录建不出来），此时从幂等集合里移除并把异常抛给容器，
 * 由 Spring Kafka 的重试机制重投——重投后能重新判一次，而不是被自己的幂等挡掉。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mockverse.judge.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class JudgeRequestConsumer {

    /** 等结果写进分区的最长时间。判题本身有上限（编译 20s + 运行 10s），发送不该比它还慢。 */
    private static final long SEND_TIMEOUT_SECONDS = 10L;

    private final JavaJudge judge;
    private final JudgeResultProducer producer;
    private final ObjectMapper objectMapper;

    /** 已处理（或正在处理）的提交 ID。见类注释里关于"进程内幂等"的边界说明。 */
    private final Set<String> processed = ConcurrentHashMap.newKeySet();

    @KafkaListener(
            topics = "${mockverse.judge.kafka.request-topic:judge-request}",
            groupId = "${mockverse.judge.kafka.group-id:judge-service}")
    public void onRequest(String payload, Acknowledgment ack) {
        JudgeRequestMessage request = parse(payload);
        if (request == null) {
            // 反序列化不了的消息重投多少次都一样，直接 ack 丢弃并留痕（毒消息不阻塞分区）
            ack.acknowledge();
            return;
        }

        String submissionId = request.submissionId();
        if (!processed.add(submissionId)) {
            log.info("judge-request 重复消息，跳过判题（幂等）: submissionId={}", submissionId);
            ack.acknowledge();
            return;
        }

        long start = System.currentTimeMillis();
        try {
            JudgeResult result = judge.judge(
                    request.code(), request.stdin(), request.expectedOutput(), request.timeoutSeconds());
            log.info("judge-request 判题完成: submissionId={}, className={}, compiled={}, ran={}, "
                            + "timedOut={}, exit={}, matched={}, {}ms, 消费到结果共 {}ms",
                    submissionId, result.className(), result.compiled(), result.ran(),
                    result.timedOut(), result.exitCode(), result.outputMatched(),
                    result.durationMs(), System.currentTimeMillis() - start);
            // 等结果真正写进分区再 ack（五查修正）：send() 是异步的，不等就等于
            // "判完了、结果没发出去、消息已经 ack"——提交方永远收不到结果
            producer.send(new JudgeResultMessage(submissionId, result))
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            ack.acknowledge();
        } catch (Exception e) {
            // 判题或发结果失败：允许重投后重判（见类注释）
            processed.remove(submissionId);
            log.error("judge-request 处理失败，不 ack（将重投）: submissionId={}", submissionId, e);
            throw new IllegalStateException("判题处理失败: submissionId=" + submissionId, e);
        }
    }

    private JudgeRequestMessage parse(String payload) {
        try {
            JudgeRequestMessage message = objectMapper.readValue(payload, JudgeRequestMessage.class);
            if (message.submissionId() == null || message.submissionId().isBlank()) {
                log.warn("judge-request 缺少 submissionId，丢弃: {}", abbreviate(payload));
                return null;
            }
            return message;
        } catch (Exception e) {
            log.warn("judge-request 无法解析，丢弃: {}, 原因: {}", abbreviate(payload), e.getMessage());
            return null;
        }
    }

    private static String abbreviate(String s) {
        return s == null ? "null" : (s.length() <= 200 ? s : s.substring(0, 200) + "…");
    }
}
