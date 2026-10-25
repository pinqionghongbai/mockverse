package com.mockverse.judge.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 把判题结果发到 {@code judge-result}（D20）。
 *
 * <p><b>为什么 value 用 String 而不是 JsonSerializer</b>：两个服务各自持有一份消息类型，
 * 用类型化序列化就得保证两侧的类结构永远同步（改一个字段忘了改另一边，运行时才炸）。
 * 这里由生产者显式序列化成 JSON 字符串，消费端按需解析——消息在 Kafka 里也是可读的，
 * 用 console-consumer 就能直接看，排障成本低。
 *
 * <p><b>key = submissionId</b>：同一个提交的请求与结果落在同一分区，消费者看到的顺序
 * 与生产顺序一致；也让"重复消息"在同一个分区里紧挨着出现，便于验证幂等。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JudgeResultProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${mockverse.judge.kafka.result-topic:judge-result}")
    private String resultTopic;

    public void send(JudgeResultMessage message) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            // 序列化失败只可能是我们自己的 record 有问题：这是**代码缺陷**，不是业务失败，
            // 抛出去让调用方（消费者）不 ack，消息重投后仍然会走到这里——所以只记日志并
            // 抛异常，不吞掉。
            throw new IllegalStateException("判题结果序列化失败: " + e.getOriginalMessage(), e);
        }
        kafkaTemplate.send(resultTopic, message.submissionId(), payload);
        log.info("judge-result 已发送: submissionId={}, topic={}", message.submissionId(), resultTopic);
    }
}
