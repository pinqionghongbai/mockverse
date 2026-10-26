package com.mockverse.interview.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Redis 访问的统一入口（D23）。
 *
 * <p><b>为什么要有这一层</b>：Redis 在本项目里是**加速器，不是依赖**。它挂了以后
 * 面试必须照常能答——题库回源 MySQL、并发锁退化为"没有锁"、限流退化为"不限"。
 * 如果把 {@code StringRedisTemplate} 直接注入到三个组件里各写一遍 try/catch，
 * 迟早有人漏掉一个分支，Redis 一抖整条面试链路就 500。
 *
 * <p><b>失败冷却</b>：与 {@code ResilientMcpToolset} 的 failure-cooldown 同一套思路。
 * Redis 宕机时每个请求都去连一次，要白等满超时；所以 yml 里把
 * {@code spring.data.redis.timeout / connect-timeout} 压到 2s（Lettuce 的默认命令超时
 * 比这长得多），再叠一层"首次失败后 30s 内直接短路、不碰连接"。
 * D23 实测：停容器后首次 start 6.17s（含一次 2s 超时 + 一次 LLM），冷却窗口内的第二次 2.72s。
 *
 * <p><b>唯一的例外是 {@link #unlock}</b>：释放锁是正确性操作，冷却期内也必须尝试，
 * 否则锁要挂到 TTL 自然过期，用户看到的是"这个会话卡住了"。详见该方法注释。
 *
 * <p><b>降级是可观测的</b>：冷却窗口内每次短路都不打日志（会刷屏），只在进入冷却时
 * WARN 一次。调用方拿到的返回值语义明确（{@code null} / {@code -1} / {@link LockResult#UNAVAILABLE}），
 * 不需要自己判断"这是没有值还是 Redis 挂了"。
 */
@Slf4j
@Component
public class RedisGateway {

    /** 分布式锁的结果。三态而不是 boolean：Redis 挂了不能等同于"锁被别人占着"。 */
    public enum LockResult { ACQUIRED, BUSY, UNAVAILABLE }

    /** 失败后的冷却窗口。取 30s 与 MCP 工具加载的 failure-cooldown 同值。 */
    private static final Duration COOLDOWN = Duration.ofSeconds(30);

    /** INCR + EXPIRE 必须原子：分两条命令时 EXPIRE 失败会留下永不过期的计数器。 */
    private static final DefaultRedisScript<Long> INCR_WITH_TTL = new DefaultRedisScript<>(
            "local v = redis.call('incr', KEYS[1]) "
                    + "if v == 1 then redis.call('pexpire', KEYS[1], ARGV[1]) end "
                    + "return v",
            Long.class);

    /**
     * 解锁必须比对持有者：直接 DEL 会把别人的锁删掉。
     * 场景——本进程的锁已过期，另一个请求拿到同名锁，此时本进程 finally 里 DEL 就会误删。
     */
    private static final DefaultRedisScript<Long> UNLOCK_IF_OWNER = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) "
                    + "else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;

    /** 冷却截止时间（epoch millis）。0 表示从未失败。 */
    private volatile long cooldownUntilMillis = 0L;

    /** 本次冷却是否已经打过 WARN。冷却结束后由 {@link #available()} 复位。 */
    private volatile boolean cooldownLogged = false;

    public RedisGateway(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Redis 当前是否可用（不在冷却窗口内）。 */
    public boolean available() {
        if (System.currentTimeMillis() >= cooldownUntilMillis) {
            cooldownLogged = false;
            return true;
        }
        return false;
    }

    /** @return 值；键不存在或 Redis 不可用时返回 {@code null}。 */
    public String get(String key) {
        if (!available()) {
            return null;
        }
        try {
            return redis.opsForValue().get(key);
        } catch (RuntimeException e) {
            degrade("GET " + key, e);
            return null;
        }
    }

    /** 写值并设过期时间。失败时静默降级（缓存写失败不影响本次请求）。 */
    public void set(String key, String value, Duration ttl) {
        if (!available()) {
            return;
        }
        try {
            redis.opsForValue().set(key, value, ttl);
        } catch (RuntimeException e) {
            degrade("SET " + key, e);
        }
    }

    /** @return 自增后的值；Redis 不可用时返回 {@code -1}。 */
    public long incr(String key, Duration ttl) {
        if (!available()) {
            return -1L;
        }
        try {
            Long value = redis.execute(INCR_WITH_TTL, List.of(key), String.valueOf(ttl.toMillis()));
            return value == null ? -1L : value;
        } catch (RuntimeException e) {
            degrade("INCR " + key, e);
            return -1L;
        }
    }

    /**
     * 尝试加锁（{@code SET key token NX PX ttl}）。
     *
     * <p>三态返回的意义：{@link LockResult#UNAVAILABLE} 时调用方应当**放行**（降级为无锁），
     * 而不是拒绝请求——Redis 挂了不该让面试停摆。
     */
    public LockResult lock(String key, String token, Duration ttl) {
        if (!available()) {
            return LockResult.UNAVAILABLE;
        }
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(key, token, ttl);
            return Boolean.TRUE.equals(acquired) ? LockResult.ACQUIRED : LockResult.BUSY;
        } catch (RuntimeException e) {
            degrade("SETNX " + key, e);
            return LockResult.UNAVAILABLE;
        }
    }

    /** 删除单个键。用于"缓存内容损坏，删掉重建"这类场景。 */
    public void delete(String key) {
        if (!available()) {
            return;
        }
        try {
            redis.delete(key);
        } catch (RuntimeException e) {
            degrade("DEL " + key, e);
        }
    }

    /**
     * 按模式删除键（用 SCAN 游标，不用 KEYS）。
     *
     * <p>{@code KEYS} 在大键空间下会阻塞 Redis 单线程，是明确的生产禁忌；
     * 即使本项目的键空间很窄，也不给后来者留下坏样例。
     *
     * <p><b>必须先收集再删除</b>：Redis 官方文档对 SCAN 的约束是"迭代期间增删元素行为未定义"，
     * 边扫边删可能漏掉键。漏掉的后果是那条缓存活到 TTL（10 分钟）——补种后抽不到新题。
     * 收集到内存再批量删，代价是几十个字符串，换确定性。
     */
    public void deleteByPattern(String pattern) {
        if (!available()) {
            return;
        }
        try (Cursor<String> cursor = redis.scan(
                ScanOptions.scanOptions().match(pattern).count(100).build())) {
            List<String> keys = new java.util.ArrayList<>();
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
            if (!keys.isEmpty()) {
                redis.delete(keys);
            }
        } catch (RuntimeException e) {
            degrade("SCAN " + pattern, e);
        }
    }

    /**
     * 释放锁（仅当 token 匹配）。重复释放、锁已过期都安全。
     *
     * <p><b>这里刻意不看冷却窗口</b>（与 get/set/incr 不同）：释放锁是**正确性操作**，
     * 不是加速器。若在冷却期内跳过，锁会一直留到 TTL 自然过期（180s）——这期间 Redis
     * 恢复了的话，同会话的请求全部 409，用户看到的是"这个会话卡住了"。
     * 代价是 Redis 宕机时每个持有过锁的请求多付一次 2s 连接超时，而这种情况本来就少
     * （冷却期内新请求根本拿不到锁，token 为 null，{@link SessionLock#release} 直接返回）。
     */
    public void unlock(String key, String token) {
        try {
            redis.execute(UNLOCK_IF_OWNER, List.of(key), token);
        } catch (RuntimeException e) {
            degrade("UNLOCK " + key, e);
        }
    }

    private void degrade(String op, RuntimeException e) {
        cooldownUntilMillis = System.currentTimeMillis() + COOLDOWN.toMillis();
        if (!cooldownLogged) {
            cooldownLogged = true;
            log.warn("Redis 不可用，接下来 {}s 内直接走数据库/不加锁（面试主流程不受影响）: {}: {}",
                    COOLDOWN.toSeconds(), op, e.getMessage());
        }
    }
}
