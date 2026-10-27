package com.mockverse.interview.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.google.adk.tools.Annotations;
import com.mockverse.interview.entity.Question;
import com.mockverse.interview.mapper.QuestionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 题库工具: 暴露给 LLM 的 {@code pick_question} 函数。
 *
 * <p><b>模型看到的名字由 {@link Annotations.Schema#name()} 显式指定</b>: ADK 默认取 Java
 * 方法名原样（{@code pickQuestion}, 见 {@code FunctionCallingUtils.buildFunctionDeclaration}）,
 * **不会**自动转下划线——D16 六查用源码核实。这里与 {@code KnowledgeTools} /
 * {@code ResumeTools} 保持同一口径: 显式声明下划线名, 让注释/文档/提示词说的是同一个名字。
 *
 * <p>查询刻意走 {@code idx_cat_diff_enabled(category, difficulty, enabled)} 的列顺序,
 * 这样能用上联合索引；随机偏移在应用层做，避免 {@code ORDER BY RAND()} 全表扫描。
 */
@Component
@RequiredArgsConstructor
public class QuestionTools {

    /** 库里的合法分类值。 */
    static final Set<String> CATEGORIES =
            Set.of("JAVA_BASIC", "JVM", "MYSQL", "REDIS", "SPRING", "NETWORK", "ALGORITHM");

    /** 库里的合法难度值。 */
    static final Set<String> DIFFICULTIES = Set.of("EASY", "MEDIUM", "HARD");

    /**
     * 别名 → 规范值（D26）。key 一律是"去掉空格/下划线/连字符的小写"。
     *
     * <p><b>为什么需要</b>：模型经常按"人话"传参——实测 A2A 链路一次出题连试了
     * {@code Java 基础} / {@code Java} 等 8 个变体，每个都打到库里返回 0 行，
     * 最后才蒙对 {@code JAVA_BASIC}。多出来的 8 次 DB 查询与 8 轮 LLM 往返全在
     * 用户等待时间里。schema 里列出合法值能降低概率，但**不能指望模型遵守**，
     * 所以工具侧必须自己兜住（与 D11 的 quote 校验同一教训）。
     */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("java", "JAVA_BASIC"),
            Map.entry("javabasic", "JAVA_BASIC"),
            Map.entry("java基础", "JAVA_BASIC"),
            Map.entry("javase", "JAVA_BASIC"),
            Map.entry("基础", "JAVA_BASIC"),
            Map.entry("jvm", "JVM"),
            Map.entry("虚拟机", "JVM"),
            Map.entry("mysql", "MYSQL"),
            Map.entry("数据库", "MYSQL"),
            Map.entry("redis", "REDIS"),
            Map.entry("缓存", "REDIS"),
            Map.entry("spring", "SPRING"),
            Map.entry("springboot", "SPRING"),
            Map.entry("network", "NETWORK"),
            Map.entry("网络", "NETWORK"),
            Map.entry("计算机网络", "NETWORK"),
            Map.entry("algorithm", "ALGORITHM"),
            Map.entry("算法", "ALGORITHM"),
            Map.entry("leetcode", "ALGORITHM"),
            Map.entry("easy", "EASY"),
            Map.entry("简单", "EASY"),
            Map.entry("初级", "EASY"),
            Map.entry("medium", "MEDIUM"),
            Map.entry("中等", "MEDIUM"),
            Map.entry("中级", "MEDIUM"),
            Map.entry("hard", "HARD"),
            Map.entry("困难", "HARD"),
            Map.entry("高级", "HARD"));

    private final QuestionMapper questionMapper;

    /**
     * 随机抽一道题。
     *
     * @param category   分类，如 JAVA_BASIC / JVM / MYSQL；也接受"Java 基础""算法"等别名；
     *                   传空表示不限
     * @param difficulty 难度，EASY / MEDIUM / HARD；也接受"简单""中等""困难"；传空表示不限
     */
    @Annotations.Schema(
            name = "pick_question",
            description = "从题库里按分类和难度随机抽一道题，返回题干、参考答案与提示；出题前先调用。"
                    + "category 取值 JAVA_BASIC / JVM / MYSQL / REDIS / SPRING / NETWORK / ALGORITHM，"
                    + "difficulty 取值 EASY / MEDIUM / HARD；不确定就留空，不要自己造词")
    public Map<String, Object> pickQuestion(String category, String difficulty) {
        String cat = normalize(category, CATEGORIES);
        String diff = normalize(difficulty, DIFFICULTIES);

        List<Question> candidates = questionMapper.selectList(
                Wrappers.<Question>lambdaQuery()
                        .eq(cat != null, Question::getCategory, cat)
                        .eq(diff != null, Question::getDifficulty, diff)
                        .eq(Question::getEnabled, true));

        Map<String, Object> result = new LinkedHashMap<>();
        if (candidates.isEmpty()) {
            result.put("found", false);
            result.put("message", "题库中没有符合条件的题目，请换一个分类或难度。");
            return result;
        }

        Question q = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        result.put("found", true);
        result.put("id", q.getId());
        result.put("category", q.getCategory());
        result.put("difficulty", q.getDifficulty());
        result.put("content", q.getContent());
        result.put("referenceAnswer", q.getReferenceAnswer());
        result.put("hint", "参考答案仅供你判断对错，禁止原文透露给候选人。");
        return result;
    }

    /**
     * 把模型传的值收敛到库里的枚举；认不出来就返回 {@code null}（= 不按该维度过滤）。
     *
     * <p><b>认不出来为什么返回 null 而不是原样透传</b>：原样透传必然查不到，
     * 模型只能反复试——那正是 D26 观测到的 8 次重试。宁可放宽条件给一道题，
     * 也不要让用户多等 8 轮往返。
     */
    /** package-private 仅为单测可见（{@code QuestionToolsTest}），不要在业务代码里直接调。 */
    static String normalize(String raw, Set<String> canonical) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        // 1) 先按"去掉分隔符的大写"比对规范值：JAVA_BASIC / java basic / java-basic 都命中
        String compact = trimmed.toUpperCase().replaceAll("[\\s_-]+", "");
        for (String c : canonical) {
            if (c.replace("_", "").equals(compact)) {
                return c;
            }
        }
        // 2) 再查别名表（同样去掉分隔符）。
        //    **必须再校验一次归属**：别名表是分类与难度共用的一张表，
        //    否则"中等"传进 category 会被转成 MEDIUM 再去查分类列 → 0 行，
        //    等于把要修的问题换个形式又造了一遍（单测 namespacesDoNotLeak 抓到）。
        String key = trimmed.toLowerCase().replaceAll("[\\s_-]+", "");
        String mapped = ALIASES.get(key);
        return mapped != null && canonical.contains(mapped) ? mapped : null;
    }
}
