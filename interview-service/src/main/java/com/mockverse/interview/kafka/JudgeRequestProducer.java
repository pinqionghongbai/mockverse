package com.mockverse.interview.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

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
     * <p><b>返回 Future 而不是 boolean</b>（二查修正）：{@code KafkaTemplate.send()} 是异步的，
     * 只有"序列化失败 / 取元数据超时"这类**同步**失败会抛出来；broker 收到消息之后才失败的
     * （重试耗尽、分区不可用）只会体现在返回的 Future 上。只返回 boolean 会让这类失败静默
     * 丢掉——提交永远停在 PENDING。
     *
     * @return 发送 Future；同步失败（序列化 / 取元数据超时）返回 {@code null}
     */
    public CompletableFuture<SendResult<String, String>> send(JudgeRequestMessage message) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            log.error("判题请求序列化失败: submissionId={}", message.submissionId(), e);
            return null;
        }
        try {
            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(requestTopic, message.submissionId(), payload);
            log.info("judge-request 已入队: submissionId={}, topic={}",
                    message.submissionId(), requestTopic);
            return future;
        } catch (RuntimeException e) {
            // Kafka 不可用：KafkaProducer.send() 会先阻塞 max.block.ms 等元数据再抛。
            // application.yml 里把这个值压到 2s —— 默认 60s 会让"提交即返回"变成"提交等一分钟"
            log.error("judge-request 发送失败（同步）: submissionId={}, topic={}",
                    message.submissionId(), requestTopic, e);
            return null;
        }
    }
}
