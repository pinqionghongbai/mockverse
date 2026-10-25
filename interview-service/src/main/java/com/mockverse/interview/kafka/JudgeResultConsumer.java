package com.mockverse.interview.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mockverse.interview.service.JudgeSubmissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 消费 {@code judge-result}，把结果交给 {@link JudgeSubmissionService} 推给客户端（D20）。
 *
 * <p><b>只做解析与转发，不重算任何东西</b>：结果里的字段由 judge-service 产出，
 * 这里原样透传给 SSE 客户端——两个服务对"判题事实"只有一个定义处。
 *
 * <p><b>幂等不在这一层做</b>：重复消息会不会重复推送，取决于
 * {@link JudgeSubmissionService#complete} 里的状态机（PENDING → COMPLETED 只走一次）。
 * 放在状态机里而不是消费者里，是因为同一个提交可能被 SSE 重连、轮询等多条路径读到，
 * 幂等必须落在状态的唯一真相处。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mockverse.interview.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class JudgeResultConsumer {

    private final ObjectMapper objectMapper;
    private final JudgeSubmissionService submissionService;

    @KafkaListener(
            topics = "${mockverse.interview.kafka.judge-result-topic:judge-result}",
            groupId = "${mockverse.interview.kafka.group-id:interview-service}")
    public void onResult(String payload) {
        JsonNode node;
        try {
            node = objectMapper.readTree(payload);
        } catch (Exception e) {
            // 解析不了的消息重投多少次都一样，丢弃并留痕（不让毒消息卡住分区）
            log.warn("judge-result 无法解析，丢弃: {}, 原因: {}", abbreviate(payload), e.getMessage());
            return;
        }
        String submissionId = node.path("submissionId").asText(null);
        if (submissionId == null || submissionId.isBlank()) {
            log.warn("judge-result 缺少 submissionId，丢弃: {}", abbreviate(payload));
            return;
        }
        JsonNode result = node.get("result");
        log.info("judge-result 到达: submissionId={}", submissionId);
        submissionService.complete(submissionId, result);
    }

    private static String abbreviate(String s) {
        return s == null ? "null" : (s.length() <= 200 ? s : s.substring(0, 200) + "…");
    }
}
