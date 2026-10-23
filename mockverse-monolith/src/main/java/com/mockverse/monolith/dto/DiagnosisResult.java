package com.mockverse.monolith.dto;

import java.util.List;

/**
 * 简历诊断的结构化结果（D11）。
 *
 * <p>三个维度是固定的：**项目经历 / 技能匹配 / 表达与量化**。固定维度而不是让模型自由发挥，
 * 是为了让不同简历之间可比——自由维度的话，每份简历的分数没法放在一起看。
 */
public record DiagnosisResult(
        int overallScore,
        List<Dimension> dimensions,
        List<Issue> issues,
        String summary) {

    /** @param name 维度名，必须是三个固定值之一 */
    public record Dimension(String name, int score, List<String> strengths, List<String> weaknesses) {}

    /**
     * @param severity HIGH / MEDIUM / LOW
     * @param quote    简历原文片段——没有原文的批评无法核对，等于没说
     */
    public record Issue(String severity, String quote, String problem, String suggestion) {}
}
