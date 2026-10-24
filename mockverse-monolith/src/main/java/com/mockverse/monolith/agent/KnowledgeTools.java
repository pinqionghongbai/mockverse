package com.mockverse.monolith.agent;

import com.google.adk.tools.Annotations;
import com.mockverse.monolith.rag.KnowledgeService;
import com.mockverse.monolith.rag.PgVectorStore;
import com.mockverse.monolith.rag.VectorStoreUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库工具: 暴露给 knowledge_agent 的 {@code search_knowledge} 函数（D12）。
 *
 * <p><b>模型看到的名字由 {@link Annotations.Schema#name()} 显式指定</b>: ADK 默认取 Java
 * 方法名原样（{@code searchKnowledge}, 见 {@code FunctionCallingUtils.buildFunctionDeclaration}）,
 * **不会**自动转下划线——D16 六查用源码与运行日志核实（resume 侧报错行写的就是
 * {@code function tool: diagnoseResume}）。提示词与文档一直写下划线形式, 所以在这里显式对齐,
 * 而不是让"提示词说 A、schema 说 B"靠模型自己猜。
 *
 * <p>返回"结构化命中列表"而不是拼好的提示词——组织语言是子 Agent 的模型的事
 * （temperature 0.2）,工具只负责确定性的检索。这样检索和生成解耦,
 * D10 的 RagAnswerService（代码拼上下文）与这条 Agentic 路线可以并存对照。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeTools {

    private final KnowledgeService knowledgeService;

    /**
     * 检索知识库。
     *
     * @param query 检索词，通常是用户的问题本身
     */
    @Annotations.Schema(
            name = "search_knowledge",
            description = "在 Java 后端知识库里做向量检索，返回带编号的资料片段；回答技术问题前先调用")
    public Map<String, Object> searchKnowledge(String query) {
        // 工具被真实调用的观测点: 没有这行日志，"Agent 自主检索"只是提示词的一厢情愿（D12 二查补）
        log.info("search_knowledge 工具被调用: {}", query);
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<PgVectorStore.Hit> hits = knowledgeService.search(query);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 0; i < hits.size(); i++) {
                PgVectorStore.Hit h = hits.get(i);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("编号", i + 1);
                row.put("来源", h.source());
                row.put("标题", h.title());
                row.put("相似度", String.format("%.3f", h.score()));
                row.put("资料内容", h.content());
                rows.add(row);
            }
            result.put("found", true);
            result.put("资料", rows);
        } catch (IllegalArgumentException | VectorStoreUnavailableException e) {
            // 向量库为空/查询为空属于"可向用户解释的失败"，返回给模型让它如实转述，
            // 而不是让整条 invocation 崩掉
            result.put("found", false);
            result.put("message", e.getMessage());
        }
        return result;
    }
}
