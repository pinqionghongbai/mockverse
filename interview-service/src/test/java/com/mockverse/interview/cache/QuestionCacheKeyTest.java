package com.mockverse.interview.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缓存键构造的单测（D29 补）。
 *
 * <p><b>为什么专门测这个</b>：D23 五轮审查在这里抓到过两个**真 bug**，两个都会让缓存
 * 改变业务语义——"请求明明指定了一个不存在的分类（本该 409），却命中全量缓存拿到随机题"。
 * 缓存层最不该犯的就是"返回错的数据"，所以这两条边界必须钉死。
 *
 * <p>纯函数测试，不起 Spring 上下文、不连 Redis。
 */
class QuestionCacheKeyTest {

    @Test
    @DisplayName("空值 / 空白 = any，不能是 *（否则和真的 category=* 撞键）")
    void blankBecomesAnyNotWildcard() {
        assertEquals("any", QuestionCache.dim(null));
        assertEquals("any", QuestionCache.dim(""));
        assertEquals("any", QuestionCache.dim("   "));

        // D23 实测的原 bug：请求带 category=* 时，键与"完全不过滤"相同
        String noFilter = QuestionCache.key(null, null);
        String literalStar = QuestionCache.key("*", null);
        assertNotEquals(noFilter, literalStar,
                "category=* 必须与\"不过滤\"是不同的键——否则缓存会返回不该拿的题");
        assertEquals("eq:" + asteriskB64(), QuestionCache.dim("*"));
    }

    /** `*` 的 Base64（URL 字母表、去 padding）：Kg */
    private static String asteriskB64() {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("*".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("含冒号的值不能拼出同一把键（拼接必须是单射）")
    void colonInValueCannotCollide() {
        // D23 复查的第二个 bug：
        //   category="A:eq:B", difficulty="C"    → eq:A:eq:B:eq:C
        //   category="A",      difficulty="B:eq:C" → eq:A:eq:B:eq:C
        String a = QuestionCache.key("A:eq:B", "C");
        String b = QuestionCache.key("A", "B:eq:C");
        assertNotEquals(a, b, "含冒号的不同查询不能共享缓存");
    }

    @Test
    @DisplayName("不同维度组合得到不同键；相同组合得到相同键")
    void keysAreStableAndDistinct() {
        assertEquals(QuestionCache.key("JAVA_BASIC", "MEDIUM"),
                QuestionCache.key("JAVA_BASIC", "MEDIUM"));
        assertNotEquals(QuestionCache.key("JAVA_BASIC", "MEDIUM"),
                QuestionCache.key("JAVA_BASIC", null));
        assertNotEquals(QuestionCache.key("JAVA_BASIC", null),
                QuestionCache.key(null, "MEDIUM"));
        assertNotEquals(QuestionCache.key("JAVA_BASIC", "MEDIUM"),
                QuestionCache.key("MYSQL", "MEDIUM"));
    }

    @Test
    @DisplayName("键前缀固定，便于批量清理与监控")
    void keyHasPrefix() {
        assertTrue(QuestionCache.key("JAVA_BASIC", "MEDIUM").startsWith("mv:question:candidates:"));
    }
}