package com.mockverse.resume.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mockverse.resume.entity.ResumeDiagnosis;
import com.mockverse.resume.service.ResumeDiagnosisService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 简历诊断接口（D11）。
 *
 * <p>历史接口把库里的 JSON 列**解析回对象**再返回：直接吐字符串的话，前端拿到的是
 * 一串带转义符的文本，还得自己 parse 一次。
 */
@RestController
@RequestMapping("/api/resume")
@RequiredArgsConstructor
public class ResumeController {

    private final ResumeDiagnosisService diagnosisService;
    private final ObjectMapper objectMapper;

    public record DiagnoseRequest(Long userId, String resumeText) {}

    @PostMapping("/diagnose")
    public Map<String, Object> diagnose(@RequestBody DiagnoseRequest request) throws JsonProcessingException {
        Long userId = request.userId() == null ? 1L : request.userId();
        ResumeDiagnosis entity = diagnosisService.diagnose(userId, request.resumeText());
        return toView(entity);
    }

    @GetMapping("/history")
    public List<Map<String, Object>> history(@RequestParam(defaultValue = "1") Long userId)
            throws JsonProcessingException {
        List<ResumeDiagnosis> rows = diagnosisService.history(userId);
        List<Map<String, Object>> views = new java.util.ArrayList<>(rows.size());
        for (ResumeDiagnosis row : rows) {
            views.add(toView(row));
        }
        return views;
    }

    private Map<String, Object> toView(ResumeDiagnosis e) throws JsonProcessingException {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", e.getId());
        view.put("userId", e.getUserId());
        view.put("overallScore", e.getOverallScore());
        // readTree 而不是 readValue(List)：这里只是转发给前端，不需要落成强类型
        view.put("dimensions", objectMapper.readTree(e.getDimensions()));
        view.put("issues", objectMapper.readTree(e.getIssues()));
        view.put("summary", e.getSummary());
        view.put("model", e.getModel());
        view.put("elapsedMs", e.getElapsedMs());
        view.put("createdAt", e.getCreatedAt());
        return view;
    }
}
