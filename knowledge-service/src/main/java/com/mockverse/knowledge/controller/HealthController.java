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
 * <p>pgvector 不可用时返回 200 + status=DEGRADED，**不抛 500**——与 PgVectorStore 的
 * 降级设计一致（"启动成功但知识库不可用"，不拖垮进程），调用方看 status 字段判断。
 * 这里必须 try/catch：`available` 只在启动时置位，运行期宕机要靠 count() 抛出的异常
 * 才能发现（D15 五轮审查第三轮实证：不 catch 的话 health 会返回 500 通用错误体，
 * 与本节契约不符）。
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

        Map<String, Object> vectorDb = new LinkedHashMap<>();
        String status = "UP";
        if (vectorStore.isAvailable()) {
            try {
                vectorDb.put("chunks", vectorStore.count());
            } catch (RuntimeException e) {
                status = "DEGRADED";
                vectorDb.put("error", e.getMessage());
            }
        } else {
            status = "DEGRADED";
        }
        body.put("status", status);
        vectorDb.put("available", "UP".equals(status));
        body.put("vectorStore", vectorDb);
        return body;
    }
}
