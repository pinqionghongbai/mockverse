package com.mockverse.interview.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 会话级分布式锁（D23）——还 D13 四查记的账。
 *
 * <p><b>要解决的问题</b>：{@code answer} / {@code answerStream} 对同一 sessionId 并发时，
 * 两个请求各自 {@code rebuildSession}（互相覆盖 ADK 会话）、各自算 {@code round}、
 * 各自 {@code nextSeq}，最后靠 {@code uk_session_seq} 唯一索引兜底成 500。
 * 单人 5 轮正常使用不会并发，但前端"双击提交""SSE 断开后重试"两个场景都会触发。
 *
 * <p><b>为什么是"会话级"而不是"方法级 {@code synchronized}"</b>（D13 的原话）：
 * 流式路径的临界区必须覆盖**整个流生命周期**（订阅开始到 {@code defer} 落库完成），
 * 方法级锁在 {@code answerStream} 返回 {@code Flowable} 的那一刻就释放了，形同虚设。
 * 因此锁由调用方在流的 {@code doFinally} 里释放。
 *
 * <p><b>Redis 挂了怎么办</b>：{@link RedisGateway#lock} 返回
 * {@link RedisGateway.LockResult#UNAVAILABLE} 时**放行**（降级为无锁，回到 D13 之前的行为），
 * 而不是拒绝请求。锁是"防并发破坏"，不是"业务准入"——Redis 故障不该让面试停摆。
 *
 * <p><b>锁的持有者是 UUID 而不是线程名</b>：解锁时比对 token，避免"锁已过期、别人拿到了、
 * 本进程 finally 里 DEL 误删别人的锁"。这是 Redis 分布式锁的基本要求，写死在
 * {@link RedisGateway} 的 Lua 脚本里。
 */
@Slf4j
@Component
public class SessionLock {

    private static final String KEY_PREFIX = "mv:lock:session:";

    private final RedisGateway redis;
    private final RedisFeatureProperties props;

    public SessionLock(RedisGateway redis, RedisFeatureProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /**
     * 在锁保护下执行一段同步逻辑。
     *
     * @throws IllegalStateException 锁被另一个请求占着（HTTP 409，语义是"这个会话正在处理中"）
     */
    public <T> T withLock(Long sessionId, Supplier<T> action) {
        String key = KEY_PREFIX + sessionId;
        String token = UUID.randomUUID().toString();
        Duration ttl = props.getSessionLockTtl();

        RedisGateway.LockResult result = redis.lock(key, token, ttl);
        if (result == RedisGateway.LockResult.BUSY) {
            throw new IllegalStateException("该会话正在处理中，请等待当前回答完成后再提交");
        }
        if (result == RedisGateway.LockResult.UNAVAILABLE) {
            log.debug("Redis 不可用，会话 {} 降级为无锁执行（回到 D13 之前的行为）", sessionId);
            return action.get();
        }
        try {
            return action.get();
        } finally {
            redis.unlock(key, token);
        }
    }

    /**
     * 流式路径专用：拿锁 → 交还 token，由调用方在流终止时释放。
     *
     * <p>为什么不复用 {@link #withLock}：流式方法返回的是 {@code Flowable}，
     * 真正的工作在订阅之后才发生，锁必须活到 {@code doFinally}。
     * 返回 {@code null} 表示"Redis 不可用，无需释放"——调用方据此跳过 unlock。
     *
     * @return 持有者 token（Redis 不可用时为 {@code null}）
     * @throws IllegalStateException 锁被另一个请求占着
     */
    public String tryAcquire(Long sessionId) {
        String key = KEY_PREFIX + sessionId;
        String token = UUID.randomUUID().toString();
        RedisGateway.LockResult result = redis.lock(key, token, props.getSessionLockTtl());
        if (result == RedisGateway.LockResult.BUSY) {
            throw new IllegalStateException("该会话正在处理中，请等待当前回答完成后再提交");
        }
        if (result == RedisGateway.LockResult.UNAVAILABLE) {
            log.debug("Redis 不可用，会话 {} 的流式请求降级为无锁执行", sessionId);
            return null;
        }
        return token;
    }

    /** 释放 {@link #tryAcquire} 拿到的锁。{@code token} 为 null（降级路径）时什么都不做。 */
    public void release(Long sessionId, String token) {
        if (token == null) {
            return;
        }
        redis.unlock(KEY_PREFIX + sessionId, token);
    }
}
