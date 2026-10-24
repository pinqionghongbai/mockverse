package com.mockverse.resume.agent;

/**
 * 简历诊断提示词（D11）。
 *
 * <p><b>为什么输出 schema 要写死在提示词里</b>：`response_format=json_object` 只保证
 * "输出是合法 JSON"，**不保证字段名、不保证维度个数、不保证分数范围**。真正的结构约束
 * 必须靠提示词里的示例 + 代码侧的校验。只依赖 json_object 是最常见的误解。
 *
 * <p>提示词里必须出现 "json" 字样（DeepSeek 的硬性要求，实测缺了直接 400：
 * {@code Prompt must contain the word 'json' in some form}）。
 */
public final class ResumeDiagnosisPrompts {

    private ResumeDiagnosisPrompts() {}

    /** 简历正文的占位符。用 replace 而不是 String.formatted，是因为提示词里有大量 `{}`。 */
    private static final String RESUME_PLACEHOLDER = "{{RESUME}}";

    public static final String SYSTEM = """
            你是一名有 10 年经验的 Java 后端技术面试官，同时负责筛选简历。
            你的评价风格：严格、具体、只指出可验证的问题，不说鼓励式套话。
            """;

    private static final String USER_TEMPLATE = """
            请诊断下面这份简历，并**只输出一个 json 对象**，不要有任何解释文字、不要用 markdown 代码块包裹。

            json 结构必须严格如下：
            {
              "overallScore": <0-100 的整数>,
              "dimensions": [
                {"name": "项目经历", "score": <0-100>, "strengths": ["..."], "weaknesses": ["..."]},
                {"name": "技能匹配", "score": <0-100>, "strengths": ["..."], "weaknesses": ["..."]},
                {"name": "表达与量化", "score": <0-100>, "strengths": ["..."], "weaknesses": ["..."]}
              ],
              "issues": [
                {"severity": "HIGH|MEDIUM|LOW", "quote": "简历原文片段", "problem": "问题是什么", "suggestion": "怎么改"}
              ],
              "summary": "一句话总评，不超过 60 字"
            }

            诊断标准：
            1. **项目经历**：是否有可验证的技术深度（规模、难点、你具体做了什么），而不是"参与开发"这种空话。
            2. **技能匹配**：Java 后端岗位的关键词覆盖（Spring / MySQL / Redis / 并发 / JVM / 分布式），
               以及是否把"了解"和"熟练"混为一谈。
            3. **表达与量化**：是否有数字（QPS、数据量、耗时下降百分比），是否用了 STAR 结构。
            4. `issues` 至少 3 条，最多 6 条，按 severity 从高到低排列。
            5. `quote` **必须逐字摘自简历原文**，不要改写——没有原文的批评无法核对。
            6. 分数要有区分度：一份普通应届简历大概 55-70 分，不要给所有人 80+。

            简历正文：
            ---
            {{RESUME}}
            ---
            """;

    public static String user(String resumeText) {
        return USER_TEMPLATE.replace(RESUME_PLACEHOLDER, resumeText);
    }
}
