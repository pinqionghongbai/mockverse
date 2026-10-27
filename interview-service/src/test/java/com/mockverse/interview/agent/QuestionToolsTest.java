package com.mockverse.interview.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code pick_question} 的入参归一化（D26）。
 *
 * <p>背景：模型会把分类传成"Java 基础""算法"这类人话，原实现直接拿去查库 → 0 行 →
 * 模型反复重试（实测一次出题连试 8 个变体）。这组用例钉住三件事：
 * <ol>
 *   <li>规范值本身（含大小写/分隔符变体）必须原样通过；</li>
 *   <li>常见别名要能收敛；</li>
 *   <li>认不出来返回 null（= 不按该维度过滤），而不是原样透传。</li>
 * </ol>
 *
 * <p>纯函数测试，不起 Spring 上下文、不连库。
 */
class QuestionToolsTest {

    @Test
    @DisplayName("规范值与其大小写/分隔符变体原样通过")
    void canonicalValuesPassThrough() {
        for (String c : new String[]{"JAVA_BASIC", "java_basic", "Java-Basic", "java basic", "JVM", "ALGORITHM"}) {
            String got = QuestionTools.normalize(c, QuestionTools.CATEGORIES);
            assertEquals(got.toUpperCase().replace(' ', '_').replace('-', '_'), got,
                    "应当返回规范值本身: " + c + " -> " + got);
        }
    }

    @Test
    @DisplayName("中文与口语别名收敛到规范值")
    void aliasesAreNormalized() {
        assertEquals("JAVA_BASIC", QuestionTools.normalize("Java 基础", QuestionTools.CATEGORIES));
        assertEquals("JAVA_BASIC", QuestionTools.normalize("Java", QuestionTools.CATEGORIES));
        assertEquals("JAVA_BASIC", QuestionTools.normalize("基础", QuestionTools.CATEGORIES));
        assertEquals("ALGORITHM", QuestionTools.normalize("算法", QuestionTools.CATEGORIES));
        assertEquals("MYSQL", QuestionTools.normalize("数据库", QuestionTools.CATEGORIES));
        assertEquals("NETWORK", QuestionTools.normalize("计算机网络", QuestionTools.CATEGORIES));

        assertEquals("MEDIUM", QuestionTools.normalize("中等", QuestionTools.DIFFICULTIES));
        assertEquals("EASY", QuestionTools.normalize("简单", QuestionTools.DIFFICULTIES));
        assertEquals("HARD", QuestionTools.normalize("困难", QuestionTools.DIFFICULTIES));
        assertEquals("MEDIUM", QuestionTools.normalize("medium", QuestionTools.DIFFICULTIES));
    }

    @Test
    @DisplayName("认不出来 / 空白 一律返回 null（不过滤该维度）")
    void unknownAndBlankBecomeNull() {
        assertNull(QuestionTools.normalize("随便写的", QuestionTools.CATEGORIES));
        assertNull(QuestionTools.normalize("", QuestionTools.CATEGORIES));
        assertNull(QuestionTools.normalize("   ", QuestionTools.CATEGORIES));
        assertNull(QuestionTools.normalize(null, QuestionTools.CATEGORIES));
        assertNull(QuestionTools.normalize("xxx", QuestionTools.DIFFICULTIES));
    }

    @Test
    @DisplayName("分类与难度不串味：难度词不会命中分类，反之亦然")
    void namespacesDoNotLeak() {
        assertNull(QuestionTools.normalize("中等", QuestionTools.CATEGORIES));
        assertNull(QuestionTools.normalize("算法", QuestionTools.DIFFICULTIES));
    }
}
