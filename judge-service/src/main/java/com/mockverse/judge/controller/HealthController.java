package com.mockverse.judge.controller;

import com.mockverse.judge.config.JudgeProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 存活探针。MCP 端点本身不适合做健康检查（要带协议报文），所以单开一个 GET。
 */
@RestController
@RequiredArgsConstructor
public class HealthController {

    private final JudgeProperties properties;

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", "judge-service");
        body.put("mcpEndpoint", properties.getEndpoint());
        body.put("maxRunTimeoutSeconds", properties.getMaxRunTimeout().toSeconds());
        body.put("maxConcurrent", properties.getMaxConcurrent());
        return body;
    }
}
