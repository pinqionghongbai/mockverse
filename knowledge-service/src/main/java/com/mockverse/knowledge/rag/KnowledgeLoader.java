package com.mockverse.knowledge.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 读取 classpath 下的 {@code knowledge/*.md} 并按 {@code ## } 小节切块。
 *
 * <p>语料是 D9 手写的 50 篇八股笔记，格式统一（一级标题是文件说明，{@code ## } 才是正文小节），
 * 所以不需要引入通用的递归字符分割器——**按已知格式做确定性切分，比上通用分割器更可控**。
 * 哪天语料格式变了，这里会直接暴露问题（切出来的块数对不上），而不是悄悄产生一堆碎块。
 */
@Slf4j
@Component
public class KnowledgeLoader {

    private static final String PATTERN = "classpath*:knowledge/*.md";

    public List<KnowledgeChunk> loadAll() {
        List<Resource> files = resolve();
        List<KnowledgeChunk> chunks = new ArrayList<>();
        for (Resource file : files) {
            String filename = file.getFilename();
            if (filename == null) {
                continue;
            }
            String category = filename.replace(".md", "").replace('-', '_').toUpperCase();
            chunks.addAll(split(filename, category, read(file)));
        }
        log.info("语料加载完成: {} 个文件, {} 个知识块", files.size(), chunks.size());
        return chunks;
    }

    private List<Resource> resolve() {
        try {
            Resource[] found = new PathMatchingResourcePatternResolver().getResources(PATTERN);
            List<Resource> list = new ArrayList<>(List.of(found));
            // 文件系统顺序不稳定, 排序保证每次入库顺序一致(便于对账)
            list.sort(Comparator.comparing(r -> String.valueOf(r.getFilename())));
            return list;
        } catch (IOException e) {
            throw new IllegalStateException("读取 knowledge 目录失败", e);
        }
    }

    private String read(Resource file) {
        try (InputStream in = file.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取语料失败: " + file.getFilename(), e);
        }
    }

    /** 按 {@code ## } 切；跳过一级标题与引用块。 */
    private List<KnowledgeChunk> split(String source, String category, String text) {
        List<KnowledgeChunk> result = new ArrayList<>();
        String currentTitle = null;
        StringBuilder body = new StringBuilder();

        for (String line : text.split("\n", -1)) {
            if (line.startsWith("## ")) {
                flush(result, category, source, currentTitle, body);
                currentTitle = line.substring(3).trim();
                body.setLength(0);
            } else if (currentTitle != null) {
                body.append(line).append('\n');
            }
            // currentTitle == null 时是文件头(# 标题 / > 说明行), 直接丢弃
        }
        flush(result, category, source, currentTitle, body);
        return result;
    }

    private void flush(List<KnowledgeChunk> out, String category, String source,
                       String title, StringBuilder body) {
        if (title == null) {
            return;
        }
        String content = body.toString().trim();
        if (content.isEmpty()) {
            // 空小节不入库: 0 向量的块会污染检索结果
            log.warn("跳过空小节: {} / {}", source, title);
            return;
        }
        out.add(new KnowledgeChunk(category, source, title, content));
    }
}
