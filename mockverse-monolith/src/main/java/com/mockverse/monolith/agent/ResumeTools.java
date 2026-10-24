package com.mockverse.monolith.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.tools.ToolContext;
import com.mockverse.monolith.dto.DiagnosisResult;
import com.mockverse.monolith.entity.ResumeDiagnosis;
import com.mockverse.monolith.service.ResumeDiagnosisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 简历诊断工具: 暴露给 resume_agent 的 {@code diagnose_resume} 函数（D12）。
 *
 * <p>内部完整复用 D11 的诊断链路（json_object + 三层语义校验 + 落库）,
 * 本工具只做"结构化结果 → 可转述的 Map"这一层翻译, 不重复任何校验逻辑。
 *
 * <p><b>参数名必须是 {@code toolContext}</b>: ADK 按参数名注入运行时上下文
 * （FunctionTool.buildArguments 里对 "toolContext" 名字有特判）,
 * 由此才能拿到会话所属的 userId——诊断记录要归档到正确的用户名下,
 * userId 绝不能让模型来传（它会编）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResumeTools {

    private final ResumeDiagnosisService diagnosisService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 诊断一份简历并落库。
     *
     * @param resumeText  简历原文，由模型从用户消息里完整传入
     * @param toolContext ADK 注入的运行时上下文（非模型参数，不进 schema）
     */
    public Map<String, Object> diagnoseResume(String resumeText, ToolContext toolContext) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Long userId = resolveUserId(toolContext);
            ResumeDiagnosis saved = diagnosisService.diagnose(userId, resumeText);
            translate(saved, result);
        } catch (IllegalArgumentException e) {
            // 简历太短/太长/为空: 用户输入问题, 返回给模型如实转述, 不让会话崩
            result.put("ok", false);
            result.put("error", e.getMessage());
        }
        // 其余异常（模型输出非法 JSON、DeepSeek 不可用等）刻意不 catch——
        // 那是 D12 下午"断掉子 Agent"要实测的失败形态, 让它沿 ADK 链路抛出去
        return result;
    }

    // ------------------------------------------------------------------

    /** 把落库实体翻译成给模型转述用的 Map。转述的数据永远和库里的对得上。 */
    private void translate(ResumeDiagnosis saved, Map<String, Object> result) {
        try {
            List<DiagnosisResult.Dimension> dims = objectMapper.readValue(
                    saved.getDimensions(), new TypeReference<List<DiagnosisResult.Dimension>>() {});
            List<DiagnosisResult.Issue> issues = objectMapper.readValue(
                    saved.getIssues(), new TypeReference<List<DiagnosisResult.Issue>>() {});

            List<Map<String, Object>> dimRows = new ArrayList<>();
            for (DiagnosisResult.Dimension d : dims) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("维度", d.name());
                row.put("得分", d.score());
                if (d.strengths() != null && !d.strengths().isEmpty()) {
                    row.put("亮点", d.strengths());
                }
                if (d.weaknesses() != null && !d.weaknesses().isEmpty()) {
                    row.put("不足", d.weaknesses());
                }
                dimRows.add(row);
            }
            List<Map<String, Object>> issueRows = new ArrayList<>();
            for (DiagnosisResult.Issue i : issues) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("严重度", i.severity());
                row.put("原文引用", i.quote());
                row.put("问题", i.problem());
                if (i.suggestion() != null && !i.suggestion().isBlank()) {
                    row.put("建议", i.suggestion());
                }
                issueRows.add(row);
            }

            result.put("ok", true);
            result.put("总分", saved.getOverallScore());
            result.put("维度明细", dimRows);
            result.put("问题清单", issueRows);
            result.put("总评", saved.getSummary());
            result.put("诊断记录ID", saved.getId());
        } catch (Exception e) {
            throw new IllegalStateException("诊断记录反序列化失败: " + e.getMessage(), e);
        }
    }

    private static Long resolveUserId(ToolContext toolContext) {
        String userId = toolContext.userId();
        try {
            return Long.valueOf(userId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("会话 userId 不是合法数字: " + userId);
        }
    }
}
