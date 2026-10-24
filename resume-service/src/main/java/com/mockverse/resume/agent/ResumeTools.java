package com.mockverse.resume.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.tools.Annotations;
import com.google.adk.tools.ToolContext;
import com.mockverse.resume.dto.DiagnosisResult;
import com.mockverse.resume.entity.ResumeDiagnosis;
import com.mockverse.resume.service.ResumeDiagnosisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 简历诊断工具: 暴露给 resume_agent 的 {@code diagnose_resume} 函数（D12）。
 *
 * <p><b>模型看到的名字由 {@link Annotations.Schema#name()} 显式指定</b>: ADK 默认取 Java
 * 方法名原样（{@code diagnoseResume}, 见 {@code FunctionCallingUtils.buildFunctionDeclaration}）,
 * **不会**自动转下划线。本类日志里的 {@code function tool: diagnoseResume} 就是这个原样名——
 * 提示词写的是 {@code diagnose_resume}, 两者不一致时只能靠模型自己看 schema 纠正, 不可依赖。
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
public class ResumeTools {

    /** ADK 的 AgentExecutor 把 A2A 请求的 params.metadata 原样放进 RunConfig.customMetadata 的这个键。 */
    private static final String A2A_METADATA_KEY = "a2a_metadata";

    private final ResumeDiagnosisService diagnosisService;
    private final long a2aDefaultUserId;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ResumeTools(ResumeDiagnosisService diagnosisService,
                       @Value("${mockverse.resume.a2a-default-user-id:0}") long a2aDefaultUserId) {
        this.diagnosisService = diagnosisService;
        this.a2aDefaultUserId = a2aDefaultUserId;
    }

    /**
     * 诊断一份简历并落库。
     *
     * @param resumeText  简历原文，由模型从用户消息里完整传入
     * @param toolContext ADK 注入的运行时上下文（非模型参数，不进 schema）
     */
    @Annotations.Schema(
            name = "diagnose_resume",
            description = "对简历原文做结构化诊断并落库，返回总分、三个维度分和逐条问题")
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

    /**
     * 解析诊断记录归属的用户。
     *
     * <p>两条来源，优先级从高到低：
     * <ol>
     *   <li><b>A2A 调用方显式传的</b> {@code params.metadata.userId}——ADK 的 AgentExecutor
     *       会把它塞进 {@code RunConfig.customMetadata} 的 {@code a2a_metadata} 键。
     *       D17 的 RemoteA2AAgent 代用户调用本服务时必须带它，否则诊断记录没法归档到正确用户名下。</li>
     *   <li><b>ADK 的 session userId 本身是数字</b>——进程内调用（单体/D12 时代）就是这种形态。</li>
     * </ol>
     *
     * <p><b>兜底用 {@code a2aDefaultUserId}（默认 0 = 匿名）</b>：ADK 的 A2A 适配层会把 userId
     * 强制变成 {@code A2A_USER_<contextId>}（见 AgentExecutor.getUserId），永远不是数字。
     * 直接抛异常会让"不带 metadata 的 A2A 调用"整条链路失败（D16 实测：模型只能回一句
     * "诊断没跑起来"）；落到 0 至少能落库，并在日志里留下是谁没带。
     */
    private Long resolveUserId(ToolContext toolContext) {
        Long fromMetadata = userIdFromA2aMetadata(toolContext);
        if (fromMetadata != null) {
            return fromMetadata;
        }
        String userId = toolContext.userId();
        try {
            return Long.valueOf(userId);
        } catch (NumberFormatException e) {
            log.warn("A2A 调用未提供 metadata.userId，诊断记录归档到默认用户 {}（会话 userId={}）",
                    a2aDefaultUserId, userId);
            return a2aDefaultUserId;
        }
    }

    /** 从 A2A 请求元数据里取调用方声明的 userId；没有/格式不对都返回 null（交给下一级兜底）。 */
    private static Long userIdFromA2aMetadata(ToolContext toolContext) {
        Object metadata = toolContext.invocationContext().runConfig()
                .customMetadata().get(A2A_METADATA_KEY);
        if (!(metadata instanceof Map<?, ?> map)) {
            return null;
        }
        Object raw = map.get("userId");
        if (raw == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(raw));
        } catch (NumberFormatException e) {
            log.warn("A2A metadata.userId 不是合法数字: {}", raw);
            return null;
        }
    }
}
