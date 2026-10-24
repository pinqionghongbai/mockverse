package com.mockverse.knowledge.controller;

import com.mockverse.knowledge.rag.KnowledgeService;
import com.mockverse.knowledge.rag.PgVectorStore;
import com.mockverse.knowledge.rag.RagAnswerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 知识库接口：检索 / RAG 问答 / 重建索引。 */
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final RagAnswerService ragAnswerService;

    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam String q,
                                      @RequestParam(defaultValue = "3") int topK) {
        List<PgVectorStore.Hit> hits = knowledgeService.search(q, topK);
        return Map.of("query", q, "count", hits.size(), "hits", hits);
    }

    /** 检索 + 生成，回答里带 [编号] 引用。 */
    @GetMapping("/ask")
    public RagAnswerService.Answer ask(@RequestParam String q) {
        return ragAnswerService.answer(q);
    }

    @PostMapping("/reindex")
    public Map<String, Object> reindex() {
        return Map.of("indexed", knowledgeService.reindex());
    }
}
