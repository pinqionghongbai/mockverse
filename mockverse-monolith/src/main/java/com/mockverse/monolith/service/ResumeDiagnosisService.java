package com.mockverse.monolith.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mockverse.monolith.agent.ResumeDiagnosisPrompts;
import com.mockverse.monolith.dto.DiagnosisResult;
import com.mockverse.monolith.entity.ResumeDiagnosis;
import com.mockverse.monolith.mapper.ResumeDiagnosisMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 简历诊断服务（D11）：调用模型 → 校验 JSON → 落库。
 *
 * <p><b>核心难点不是"让模型输出 JSON"，是"让输出能被信任"。</b>
 * {@code response_format=json_object} 只保证语法合法，字段名拼错、维度少一个、分数给 120，
 * 它一样放行。所以这里做了三层防线：
 * <ol>
 *   <li><b>提示词给死 schema</b>（含字段名与取值约束）</li>
 *   <li><b>解析时容忍噪音</b>：模型偶尔会包一层 ```json 代码块，先剥掉再解析</li>
 *   <li><b>解析后做语义校验</b>：维度名必须是固定的三个、分数必须落在 0-100、问题至少一条。
 *       校验失败抛异常，**不把半成品落库**——脏数据比没有数据更难处理</li>
 * </ol>
 */
@Slf4j
@Service
public class ResumeDiagnosisService {

    /** 三个固定维度。固定而不是让模型自由发挥，是为了不同简历之间分数可比。 */
    private static final Set<String> REQUIRED_DIMENSIONS = Set.of("项目经历", "技能匹配", "表达与量化");

    private static final int MIN_LENGTH = 20;
    private static final int MAX_LENGTH = 8000;

    /** 历史列表上限。每行含完整简历原文，不封顶会把内存撑爆。 */
    private static final int HISTORY_LIMIT = 50;

    /** 问题严重度的合法取值，与提示词里的 schema 必须一致。 */
    private static final Set<String> SEVERITIES = Set.of("HIGH", "MEDIUM", "LOW");

    private final ResumeDiagnosisMapper mapper;
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final String modelName;

    public ResumeDiagnosisService(ResumeDiagnosisMapper mapper,
                                  @Qualifier("diagnosisChatModel") ChatModel chatModel,
                                  ObjectMapper objectMapper,
                                  @Value("${mockverse.deepseek.model:deepseek-chat}") String modelName) {
        this.mapper = mapper;
        this.chatModel = chatModel;
        // 模型偶尔多返回一个字段，不该让整次诊断失败
        this.objectMapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                // 这一条是必须的：Jackson 对 record 的 int 字段，**字段缺失时会静默填 0**。
                // 模型少给一个 overallScore，我们就会落库一条"0 分"的合法诊断，校验层还放行。
                // 打开后缺失或显式 null 都抛异常，而不是变成一个看起来合理的 0。
                .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true);
        this.modelName = modelName;
    }

    public ResumeDiagnosis diagnose(Long userId, String resumeText) {
        validateInput(resumeText);

        long begin = System.currentTimeMillis();
        // ChatModel.chat(List) 返回 ChatResponse，要 .aiMessage().text() 才是正文字符串
        String raw = chatModel.chat(List.of(
                SystemMessage.from(ResumeDiagnosisPrompts.SYSTEM),
                UserMessage.from(ResumeDiagnosisPrompts.user(resumeText))))
                .aiMessage().text();
        long elapsed = System.currentTimeMillis() - begin;

        DiagnosisResult result = parseAndValidate(raw);
        int unverified = warnIfQuoteNotVerbatim(result, resumeText);
        log.info("简历诊断完成: 总分={} 问题数={} quote未命中原文={} 耗时={}ms",
                result.overallScore(), result.issues().size(), unverified, elapsed);

        return persist(userId, resumeText, result, (int) elapsed);
    }

    /**
     * 最近的诊断记录。
     *
     * <p>必须带上限：每行都含完整简历原文（最多 8000 字），不封顶的话用户诊断几十次后
     * 一次查询就能把几 MB 文本捞进内存。
     */
    public List<ResumeDiagnosis> history(Long userId) {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<ResumeDiagnosis> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, HISTORY_LIMIT, false);
        return mapper.selectPage(page,
                        com.baomidou.mybatisplus.core.toolkit.Wrappers.<ResumeDiagnosis>lambdaQuery()
                                .eq(ResumeDiagnosis::getUserId, userId)
                                .orderByDesc(ResumeDiagnosis::getCreatedAt))
                .getRecords();
    }

    // ------------------------------------------------------------------

    private void validateInput(String resumeText) {
        if (resumeText == null || resumeText.isBlank()) {
            throw new IllegalArgumentException("简历内容不能为空");
        }
        if (resumeText.length() < MIN_LENGTH) {
            throw new IllegalArgumentException("简历内容太短（至少 " + MIN_LENGTH + " 字），无法诊断");
        }
        if (resumeText.length() > MAX_LENGTH) {
            // 不做静默截断：截断会让模型基于残缺内容给分，用户却以为诊断的是全文
            throw new IllegalArgumentException("简历内容过长（上限 " + MAX_LENGTH + " 字），请精简后重试");
        }
    }

    /**
     * 剥掉可能的 markdown 代码块，再解析 + 语义校验。
     *
     * <p>可见性为 public 是**刻意**的：冒烟脚本要能绕过模型、直接喂脏 JSON 来证明校验层真的拦得住。
     * 只测"好输入能通过"等于没测——真正的风险全在坏输入上。
     */
    public DiagnosisResult parseAndValidate(String raw) {
        String json = stripCodeFence(raw);
        DiagnosisResult result;
        try {
            result = objectMapper.readValue(json, DiagnosisResult.class);
        } catch (JsonProcessingException e) {
            // 把原始输出截一段带进异常，否则排查时完全不知道模型吐了什么
            throw new IllegalStateException(
                    "模型返回的不是合法 JSON: " + e.getOriginalMessage()
                            + " | 原始输出片段: " + abbreviate(raw), e);
        }
        checkSemantics(result);
        return result;
    }

    private void checkSemantics(DiagnosisResult r) {
        if (r.overallScore() < 0 || r.overallScore() > 100) {
            throw new IllegalStateException("总分越界: " + r.overallScore());
        }
        if (r.dimensions() == null || r.dimensions().size() != REQUIRED_DIMENSIONS.size()) {
            throw new IllegalStateException("维度数量应为 " + REQUIRED_DIMENSIONS.size() + "，实际: "
                    + (r.dimensions() == null ? 0 : r.dimensions().size()));
        }
        Set<String> got = new java.util.HashSet<>();
        for (DiagnosisResult.Dimension d : r.dimensions()) {
            // 必须先判 null 再查集合：Set.of() 是不可变集合，contains(null) 会抛 NPE，
            // 那就变成 500 而不是"模型输出不合格"的 409 —— 错误码会把排查方向带偏
            if (d == null || d.name() == null) {
                throw new IllegalStateException("维度项或维度名为空");
            }
            if (!REQUIRED_DIMENSIONS.contains(d.name())) {
                throw new IllegalStateException("未知维度名: " + d.name());
            }
            if (d.score() < 0 || d.score() > 100) {
                throw new IllegalStateException("维度 " + d.name() + " 分数越界: " + d.score());
            }
            got.add(d.name());
        }
        if (!got.equals(REQUIRED_DIMENSIONS)) {
            throw new IllegalStateException("维度缺失，得到: " + got);
        }
        if (r.issues() == null || r.issues().isEmpty()) {
            throw new IllegalStateException("问题清单为空，诊断没有意义");
        }
        if (r.summary() == null || r.summary().isBlank()) {
            throw new IllegalStateException("总评为空");
        }
        for (DiagnosisResult.Issue issue : r.issues()) {
            if (issue == null) {
                throw new IllegalStateException("问题项为空");
            }
            // severity 是前端排序和高亮所依赖的枚举，模型给"严重""P0"这类自由值必须拦下，
            // 否则落库之后没有任何地方能用它
            if (issue.severity() == null || !SEVERITIES.contains(issue.severity())) {
                throw new IllegalStateException("非法 severity: " + issue.severity());
            }
            if (issue.quote() == null || issue.quote().isBlank()) {
                throw new IllegalStateException("issue.quote 为空，没有原文的批评无法核对");
            }
        }
    }

    /**
     * 校验 quote 是否真的出自简历原文。
     *
     * <p>提示词要求"逐字摘自简历"，但**模型说的话不能当证据**。这里只告警不抛异常，
     * 因为全角/半角、空白、省略号这类归一化差异会造成误伤——真按硬校验做，会把
     * 格式正确的诊断也拒掉。告警的意义是：一旦某天大面积出现，说明提示词在退化。
     *
     * @return 不匹配的条数，0 表示全部逐字命中
     */
    public int warnIfQuoteNotVerbatim(DiagnosisResult result, String resumeText) {
        String normalizedResume = normalize(resumeText);
        int mismatches = 0;
        for (DiagnosisResult.Issue issue : result.issues()) {
            if (!normalizedResume.contains(normalize(issue.quote()))) {
                mismatches++;
                log.warn("quote 未在原文中找到（可能是改写或归一化差异）: {}", abbreviate(issue.quote()));
            }
        }
        return mismatches;
    }

    /** 去掉所有空白，让比对不受换行和缩进影响。 */
    private static String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    private ResumeDiagnosis persist(Long userId, String resumeText, DiagnosisResult r, int elapsedMs) {
        ResumeDiagnosis entity = new ResumeDiagnosis();
        entity.setUserId(userId);
        entity.setResumeText(resumeText);
        entity.setOverallScore(r.overallScore());
        entity.setDimensions(writeJson(r.dimensions()));
        entity.setIssues(writeJson(r.issues()));
        entity.setSummary(r.summary());
        entity.setModel(modelName);
        entity.setElapsedMs(elapsedMs);
        entity.setCreatedAt(LocalDateTime.now());
        mapper.insert(entity);
        return entity;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化诊断结果失败", e);
        }
    }

    private static String stripCodeFence(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (!s.startsWith("```")) {
            return s;
        }
        // 不要按第一个换行去切：```json{"overallScore":66, 这种"标记后面紧跟内容"的写法很常见，
        // 按换行切会把第一行的 {"overallScore":66, 一起丢掉，剩下的 JSON 缺字段——
        // 而缺 int 字段会被 FAIL_ON_NULL_FOR_PRIMITIVES 拦下，报出来的错跟真正的原因完全对不上。
        // 正确做法：剥掉开头的 ``` 和语言标记，再剥掉结尾的 ```，中间的换行交给 JSON 解析器。
        String body = s.substring(3);
        if (body.startsWith("json")) {
            body = body.substring(4);
        }
        int closing = body.lastIndexOf("```");
        if (closing >= 0) {
            body = body.substring(0, closing);
        }
        return body.trim();
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String oneLine = s.replaceAll("\\s+", " ");
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 120) + "…";
    }
}
