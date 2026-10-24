package com.mockverse.interview.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查端点 —— D8 的"Spring Boot 本体实证"。
 *
 * <p>它同时证明三件事: Web 层能起、依赖注入能通、数据源能连上 MySQL。
 * 直接注入 DataSource 而不是 JdbcTemplate, 是为了不额外假设自动配置的 Bean 存在。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("app", "interview-service");
        body.put("framework", "Spring Boot 3.3.1");
        body.put("status", "UP");

        Map<String, Object> db = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection()) {
            db.put("connected", true);
            db.put("product", conn.getMetaData().getDatabaseProductName()
                    + " " + conn.getMetaData().getDatabaseProductVersion());
            db.put("catalog", conn.getCatalog());
        } catch (Exception e) {
            db.put("connected", false);
            db.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        body.put("database", db);
        return body;
    }
}
