package com.mockverse.knowledge.controller;

import com.mockverse.knowledge.rag.PgVectorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查端点（knowledge-service 副本，D15 机械拆分）。
 *
 * <p>与单体/interview-service 版的差别：本服务**没有 MySQL DataSource**，健康检查的
 * 数据面是 pgvector（由 PgVectorStore 组件私有连接池直连）。它同时证明三件事:
 * Web 层能起、依赖注入能通、向量库能连上 pgvector。
 *
 * <p>pgvector 连不上时仍返回 200 + status=DEGRADED——与 PgVectorStore 的降级设计一致
 * （"启动成功但知识库不可用"，不拖垮进程），调用方看 status 字段判断。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final PgVectorStore vectorStore;

    public HealthController(PgVectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("app", "knowledge-service");
        body.put("framework", "Spring Boot 3.3.1");
        body.put("status", vectorStore.isAvailable() ? "UP" : "DEGRADED");

        Map<String, Object> vectorDb = new LinkedHashMap<>();
        vectorDb.put("available", vectorStore.isAvailable());
        if (vectorStore.isAvailable()) {
            vectorDb.put("chunks", vectorStore.count());
        }
        body.put("vectorStore", vectorDb);
        return body;
    }
}
