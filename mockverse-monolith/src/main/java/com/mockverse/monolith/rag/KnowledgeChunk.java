package com.mockverse.monolith.rag;

/**
 * 语料切分后的一个知识块。
 *
 * <p>粒度为 markdown 里的一个 {@code ## } 小节——一篇语料 = 一个主题，一个小节 = 一个可独立检索的问答单元。
 * 这个粒度是刻意选的：比整篇切得细（整篇会稀释向量，一个问题被几十个主题平均掉），
 * 比按句切得粗（单句缺少上下文，召回后模型看不懂）。
 *
 * @param category 分类目录（JAVA_BASIC / JVM / MYSQL ...），取自文件名
 * @param source   来源文件（如 {@code java-basic.md}），用于溯源展示
 * @param title    小节标题（{@code ## } 后面的文字）
 * @param content  小节正文（不含标题行）
 */
public record KnowledgeChunk(String category, String source, String title, String content) {

    /** 送进 embedding 模型的文本：标题 + 正文，让"标题里的关键词"也能参与匹配。 */
    public String embedText() {
        return title + "\n" + content;
    }
}
