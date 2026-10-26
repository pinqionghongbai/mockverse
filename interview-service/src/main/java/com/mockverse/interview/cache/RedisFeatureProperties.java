package com.mockverse.interview.cache;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 三项能力的参数（D23）。
 *
 * <p>集中成一个类而不是散在三个组件里：这几个值都是"调参"而不是"实现细节"，
 * 写在同一处才能一眼看出缓存/锁/限流的量级关系（题库缓存 10 分钟、会话锁 3 分钟、
 * 限流 30 次/分钟）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mockverse.redis")
public class RedisFeatureProperties {

    /**
     * 题库候选集缓存时长。
     *
     * <p>题库只在启动时补种、平时不变，10 分钟足够；设短了缓存意义不大，
     * 设长了换题库要等太久（另有 {@code QuestionSeeder} 启动后主动失效）。
     */
    private Duration questionTtl = Duration.ofMinutes(10);

    /**
     * 空结果的缓存时长（防穿透）。
     *
     * <p>查询一个不存在的分类时结果为空，若不缓存，每次请求都打到 MySQL。
     * 空结果只缓存 30s：补种/改分类后最多半分钟就能看到，不会长期"查不到新题"。
     */
    private Duration emptyTtl = Duration.ofSeconds(30);

    /**
     * 会话级分布式锁的过期时间。
     *
     * <p>必须覆盖一整轮 SSE 流的生命周期：流式一轮实测 2~4s，但慢模型/判题叠加时
     * 会更长，180s 与 {@code InterviewController.SSE_TIMEOUT_MILLIS} 同值——
     * 锁比流先过期会让并发的第二个请求挤进来，锁比流长太多则客户端断开后要等更久。
     */
    private Duration sessionLockTtl = Duration.ofSeconds(180);

    /**
     * 单用户每分钟的 SSE 请求上限。
     *
     * <p>一场面试最多 5 轮，正常用户 1 分钟远到不了 30 次；这个值只挡"脚本狂刷"。
     * 与 D13 遗留的"同会话并发无防护"不同——那条靠 {@link SessionLock} 串行化，
     * 这条挡的是不同会话的批量请求。
     */
    private int ssePerMinute = 30;
}
