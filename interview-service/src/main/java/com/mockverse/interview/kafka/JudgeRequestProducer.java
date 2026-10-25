package com.mockverse.interview.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 把一次判题提交发到 {@code judge-request}（D20）。
 *
 * <p><b>key = submissionId</b>：同一提交的请求与结果落在同一分区，保证"先提交、后结果"
 * 的因果顺序在单个提交内成立；重复投递时也能在同一个分区里紧挨着出现，便于验证幂等。
 *
 * <p>value 用 String 而不是 JsonSerializer：两个服务各持一份消息类型，类型化序列化
 * 要求两边永远同步；这里显式序列化，消息在 Kafka 里也是可读的。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JudgeRequestProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${mockverse.interview.kafka.judge-request-topic:judge-request}")
    private String requestTopic;

    /**
     * 发送判题请求。
     *
     * @return 发送是否成功入队。失败不抛异常——调用方已经把 {@code submissionId} 返回给客户端了，
     *         这里再抛只会让"提交"这个动作变成 500；由调用方决定是否把状态标成 FAILED。
     */
    public boolean send(JudgeRequestMessage message) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            log.error("判题请求序列化失败: submissionId={}", message.submissionId(), e);
            return false;
        }
        try {
            kafkaTemplate.send(requestTopic, message.submissionId(), payload);
            log.info("judge-request 已发送: submissionId={}, topic={}", message.submissionId(), requestTopic);
            return true;
        } catch (RuntimeException e) {
            // Kafka 不可用：提交本身不该失败（用户已经点过"提交"了），把错误留在日志里，
            // 状态由调用方标记；D20 的验收要求是"提交即返回判题中"，不是"提交必须成功入队"
            log.error("judge-request 发送失败: submissionId={}, topic={}",
                    message.submissionId(), requestTopic, e);
            return false;
        }
    }
}
