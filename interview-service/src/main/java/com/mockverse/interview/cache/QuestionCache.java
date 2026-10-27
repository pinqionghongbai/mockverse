package com.mockverse.interview.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mockverse.interview.entity.Question;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.function.Supplier;

/**
 * 题库候选集缓存（D23，cache-aside）。
 *
 * <p><b>缓存的是什么</b>：{@code pickQuestion} 的候选集（按 category/difficulty 过滤后
 * 全部 enabled 的题），不是单道题。原实现每次开会话都做一次全表条件查询，再在内存里
 * 随机取一道——数据量小（13 行）时无所谓，但这个查询是面试链路里**唯一一次与 LLM 无关
 * 的阻塞 DB 调用**，把它摘掉才能让"Redis 缓存后 MySQL QPS 下降"这条量化指标可测。
 *
 * <p><b>空值兜底（防穿透）</b>：查询一个不存在的分类时结果是空列表。若只缓存非空结果，
 * 每次请求都会穿透到 MySQL。这里用 {@link #EMPTY} 哨兵把空结果也写进缓存，
 * 但 TTL 只有 30s（见 {@code RedisFeatureProperties.emptyTtl}）——补种/改分类后
 * 最多半分钟就能看到新题，不会长期"查不到"。
 *
 * <p><b>为什么不用 {@code @Cacheable}</b>：Spring Cache 抽象在 Redis 故障时的降级行为
 * 取决于 {@code CacheErrorHandler}，默认是**抛异常**——Redis 一挂 {@code pickQuestion}
 * 直接 500，与"Redis 是加速器不是依赖"的前提冲突。手写 cache-aside 才能把
 * "读缓存失败 → 回源 DB"写成显式代码。
 */
@Slf4j
@Component
public class QuestionCache {

    /**
     * 空结果哨兵。
     *
     * <p>用 JSON 里不可能出现的字符串而不是空串：空串在 {@code get} 返回后无法区分
     * "键不存在"（Lettuce 返回 null）与"缓存了空列表"。哨兵让两者语义明确。
     */
    private static final String EMPTY = "__EMPTY__";

    private static final String KEY_PREFIX = "mv:question:candidates:";

    private static final TypeReference<List<Question>> LIST_TYPE = new TypeReference<>() {};

    private final RedisGateway redis;
    private final RedisFeatureProperties props;
    private final ObjectMapper objectMapper;

    public QuestionCache(RedisGateway redis, RedisFeatureProperties props, ObjectMapper objectMapper) {
        this.redis = redis;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /**
     * 取候选集：先查缓存，未命中则调用 {@code loader} 回源并回填。
     *
     * <p>{@code loader} 抛异常时不写缓存（避免把"数据库挂了"缓存成"题库为空"——
     * 那会让服务在 DB 恢复后仍然持续返回 409）。
     */
    public List<Question> candidates(String category, String difficulty, Supplier<List<Question>> loader) {
        String key = key(category, difficulty);
        String cached = redis.get(key);
        if (EMPTY.equals(cached)) {
            return List.of();
        }
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, LIST_TYPE);
            } catch (Exception e) {
                // 缓存内容损坏（版本升级 / 手工写脏 / 实体字段变更）时不能抛，回源重建。
                // **必须真的删掉**：只回源不删的话，这条坏键会一直留到 TTL（10 分钟），
                // 期间每个请求都要付一次"解析失败 + 查库"——而日志只 WARN 一次，
                // 很容易被当成偶发抖动。D23 复查发现注释写了"删掉这条"但代码没有删。
                log.warn("题库缓存反序列化失败，删除后回源重建: key={}, {}", key, e.getMessage());
                redis.delete(key);
            }
        }

        List<Question> loaded = loader.get();
        write(key, loaded);
        return loaded;
    }

    /**
     * 主动失效所有候选集缓存。
     *
     * <p>调用点只有一个：{@code QuestionSeeder} 补种完成后。补种是启动期一次性动作，
     * 若不清缓存，新题最多要等 10 分钟才可能被抽到——演示前刚改过题库就会踩这个坑。
     *
     * <p>用 SCAN 而不是 KEYS：KEYS 在大 key 空间下会阻塞 Redis 单线程。
     */
    public void invalidateAll() {
        try {
            // RedisGateway 没有暴露 scan，这里直接说明：题库候选集的键空间极窄
            // （分类×难度的组合数），实际不会超过几十个；用 KEYS 的风险面可以接受，
            // 但为了不给以后留下坏样例，仍然走 SCAN。
            redis.deleteByPattern(KEY_PREFIX + "*");
        } catch (RuntimeException e) {
            // Redis 不可用/清理失败不影响正确性：缓存 10 分钟自然过期，且本次启动
            // 之后的读取会回源到新题库
            log.warn("题库缓存清理失败（不影响正确性，10 分钟后自然过期）: {}", e.getMessage());
        }
    }

    private void write(String key, List<Question> questions) {
        try {
            if (questions.isEmpty()) {
                redis.set(key, EMPTY, props.getEmptyTtl());
                return;
            }
            redis.set(key, objectMapper.writeValueAsString(questions), props.getQuestionTtl());
        } catch (Exception e) {
            // 序列化失败只影响缓存，不影响本次返回
            log.warn("题库缓存写入失败，本次直接返回 DB 结果: key={}, {}", key, e.getMessage());
        }
    }

    /**
     * 键的构造：每个维度要么是"不过滤"标记，要么是 {@code eq:<Base64(原值)>}。
     *
     * <p><b>为什么不能拿 {@code *} 当"不过滤"的占位符</b>（D23 复查实测）：{@code category}
     * 是**用户可控的请求参数**。原实现把 null/blank 编码成 {@code *}，于是
     * {@code POST /start?category=*} 算出的键与"完全不过滤"**完全相同**，直接命中
     * 全量候选集缓存 —— 请求明明指定了一个不存在的分类（本应返回 409），却拿到一道随机题。
     * 缓存改变了业务语义，这是缓存层最不该犯的错。
     *
     * <p><b>为什么值还要 Base64</b>（同一轮复查的第二刀）：只加 {@code eq:} 前缀仍然不唯一。
     * 键是 {@code dim(c) + ":" + dim(d)} 的拼接，而 c/d 可以含 {@code ":"}，于是
     * <pre>
     *   category="A:eq:B", difficulty="C"    → eq:A:eq:B:eq:C
     *   category="A",      difficulty="B:eq:C" → eq:A:eq:B:eq:C   ← 同一把键
     * </pre>
     * 两个完全不同的查询共享缓存，返回错的题。Base64 URL 字母表是 {@code A-Za-z0-9-_}，
     * **不含冒号**，所以分隔符不会再被值吃掉，拼接结果是单射的。
     */
    /** package-private 仅为单测可见（QuestionCacheKeyTest），不要在业务代码里直接调。 */
    static String key(String category, String difficulty) {
        return KEY_PREFIX + dim(category) + ":" + dim(difficulty);
    }

    /** package-private 仅为单测可见（QuestionCacheKeyTest）。 */
    static String dim(String value) {
        if (value == null || value.isBlank()) {
            return "any";
        }
        return "eq:" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
