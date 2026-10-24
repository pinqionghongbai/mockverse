package com.mockverse.interview.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mockverse.interview.entity.Question;
import com.mockverse.interview.mapper.QuestionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 题库工具: 暴露给 LLM 的 {@code pick_question} 函数。
 *
 * <p>注意 ADK 会把方法名 {@code pickQuestion} 转成 {@code pick_question} 暴露给模型,
 * 提示词里必须用下划线形式引用。
 *
 * <p>查询刻意走 {@code idx_cat_diff_enabled(category, difficulty, enabled)} 的列顺序,
 * 这样能用上联合索引；随机偏移在应用层做，避免 {@code ORDER BY RAND()} 全表扫描。
 */
@Component
@RequiredArgsConstructor
public class QuestionTools {

    private final QuestionMapper questionMapper;

    /**
     * 随机抽一道题。
     *
     * @param category   分类，如 JAVA_BASIC / JVM / MYSQL；传空表示不限
     * @param difficulty 难度，EASY / MEDIUM / HARD；传空表示不限
     */
    public Map<String, Object> pickQuestion(String category, String difficulty) {
        List<Question> candidates = questionMapper.selectList(
                Wrappers.<Question>lambdaQuery()
                        .eq(category != null && !category.isBlank(), Question::getCategory, category)
                        .eq(difficulty != null && !difficulty.isBlank(), Question::getDifficulty, difficulty)
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
}
