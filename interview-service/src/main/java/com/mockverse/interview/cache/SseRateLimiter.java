package com.mockverse.interview.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * SSE 请求限流（D23，固定窗口计数器）。
 *
 * <p><b>挡什么</b>：脚本狂刷 {@code /answer/stream}。每开一条流就是一轮 LLM 调用
 * （实测 2~9s、按 token 计费），不设上限时一个人能把 API 额度刷光。
 *
 * <p><b>为什么是固定窗口而不是滑动窗口/令牌桶</b>：面试场景"每分钟 30 次"这个量级
 * 不需要平滑。固定窗口的边界突刺（59 秒刷 30 次、61 秒再刷 30 次）对本项目无害——
 * 它防的是"持续狂刷"，不是"精确的 QPS 控制"。滑动窗口要多存一份 ZSET 并清理旧成员，
 * 收益不抵复杂度（KISS）。
 *
 * <p><b>为什么只限流 SSE</b>：{@code /start} 和同步 {@code /answer} 同样是 LLM 调用，
 * 但它们没有"长连接 + 逐字推送"的额外开销，且 {@code start} 有"题库为空返回 409"的天然
 * 节流。演示路径上被误伤的风险优先于理论完备性——只限流最贵的那个端点。
 *
 * <p><b>Redis 不可用时放行</b>：限流失败退化为"不限流"，与 {@link SessionLock} 同一口径。
 */
@Slf4j
@Component
public class SseRateLimiter {

    private static final String KEY_PREFIX = "mv:rate:sse:";

    /** 固定窗口长度：1 分钟。与 {@link RedisFeatureProperties#getSsePerMinute()} 的语义配套。 */
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final RedisGateway redis;
    private final RedisFeatureProperties props;

    public SseRateLimiter(RedisGateway redis, RedisFeatureProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /**
     * 记一次请求并判断是否超限。
     *
     * <p>计数与判断是同一次 {@code INCR} 的返回值，天然原子——不存在"先 GET 再 INCR"
     * 的检查-使用竞态。首次自增时才设过期（Lua 脚本里做），避免"设了过期但计数没加"
     * 或"计数加了但过期没设"两种半成品状态。
     *
     * @return {@code true} 表示放行
     */
    public boolean tryAcquire(Long userId) {
        // 窗口键带分钟时间戳：固定窗口靠"键自然换新"实现，不需要额外的重置逻辑
        String key = KEY_PREFIX + userId + ":" + (System.currentTimeMillis() / WINDOW.toMillis());
        long count = redis.incr(key, WINDOW);
        if (count < 0) {
            // Redis 不可用（incr 返回 -1）：放行，不因限流组件故障拒绝用户
            return true;
        }
        if (count > props.getSsePerMinute()) {
            log.warn("SSE 限流触发: userId={}, 本分钟第 {} 次（上限 {}）",
                    userId, count, props.getSsePerMinute());
            return false;
        }
        return true;
    }
}
