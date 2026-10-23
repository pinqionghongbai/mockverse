package com.mockverse.monolith.smoke;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mockverse.monolith.agent.ResumeDiagnosisPrompts;
import com.mockverse.monolith.entity.ResumeDiagnosis;
import com.mockverse.monolith.mapper.ResumeDiagnosisMapper;
import com.mockverse.monolith.service.ResumeDiagnosisService;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * D11 验收：模型原始输出 → 服务全链路 → 区分度对比 → 落库校验 → 校验层负向测试。
 *
 * <p>用法: {@code mvn spring-boot:run -Dspring-boot.run.profiles=resume}
 *
 * <p><b>为什么第一步要单独打一次原始输出</b>：{@code response_format=json_object} 到底有没有
 * 生效，只能看模型吐出来的原文——如果它包了 ```json 代码块，说明这个开关没起作用，
 * 后面所有"解析成功"都只是运气。**先看原文，再看解析结果**，顺序反了会自欺欺人。
 *
 * <p><b>为什么要诊断两份简历而不是一份</b>：只测一份时，"总分 52" 这个数字既不能说明打分准，
 * 也不能说明打分有区分度——模型给所有人 52 分也能通过这种测试。真正该验证的命题是
 * **"好的简历分数显著高于差的简历"**，这必须靠对照才能成立。绝对分数只作观察，不作断言：
 * 模型的分数天然有 ±5 上下的抖动，把断言钉死在某个区间里，测的是运气不是能力。
 */
@Component
@Profile("resume")
@Order(20)
public class ResumeSmokeRunner implements ApplicationRunner {

    /** 对照组 A：刻意写成典型的"学生简历"——全是参与/负责，没有一个数字。 */
    private static final String RESUME_WEAK = """
            姓名：张伟    求职意向：Java 后端开发    学历：本科（2024 届）
            教育背景：某大学 计算机科学与技术 2019.09-2023.06
            专业技能：
            - 熟悉 Java 基础，了解集合框架、多线程
            - 了解 Spring Boot、MyBatis，能够独立完成 CRUD 开发
            - 了解 MySQL，会写基本的 SQL 语句
            - 了解 Redis 缓存
            项目经历：
            1. 校园二手交易平台（2022.03-2022.06）
               参与开发后端接口，负责商品模块和订单模块，使用 Spring Boot + MySQL 实现。
               和队友一起完成了项目上线，获得课程优秀项目。
            2. 图书管理系统（2022.09-2022.12）
               负责后端开发，实现用户登录、图书借阅归还功能。
            实习经历：无
            自我评价：学习能力强，能吃苦，有团队合作精神。
            """;

    /** 对照组 B：有实习、有难点、有量化。用来证明分数不是"给谁都一样"。 */
    private static final String RESUME_STRONG = """
            姓名：李娜    求职意向：Java 后端开发    学历：本科（2024 届）
            教育背景：某 211 大学 软件工程 2019.09-2023.06
            专业技能：
            - 熟练 Java：理解 JVM 内存模型与 GC 机制，能读 GC 日志定位 Full GC 原因
            - 熟练 Spring Boot，熟悉 AOP、事务传播行为、自动装配原理
            - 熟练 MySQL：理解 InnoDB 索引结构与最左前缀，能用 explain 优化慢查询
            - 熟练 Redis：缓存穿透/击穿/雪崩的处理方案，分布式锁的实现与坑
            - 了解 Kafka、Docker、K8s 基本使用
            实习经历：
            某互联网公司 后端开发实习生 2022.07-2022.12
            - 负责订单模块重构：拆分为独立服务，接口 P99 从 420ms 降到 90ms
            - 用 Redis + Lua 实现分布式锁解决超卖，2000 并发压测零超卖，下单 QPS 从 800 提升到 2400
            - 主导慢查询治理：定位 7 条慢 SQL，补联合索引后平均响应从 1.2s 降到 150ms
            项目经历：
            秒杀系统（2022.03-2022.06，个人项目，GitHub 1.2k star）
            - 技术栈：Spring Boot + Redis + RabbitMQ + MySQL
            - Redis 预扣库存 + RabbitMQ 异步下单，4C8G 单机压测峰值 QPS 约 3000
            - 令牌桶限流 + 接口防刷，扛住 10 万级请求突刺，服务未雪崩
            """;

    /** 一份结构合法的最小样例，用来单独测"剥离代码块"这一层，不掺模型的不确定性。 */
    private static final String SAMPLE_JSON = """
            {"overallScore":66,"dimensions":[
              {"name":"项目经历","score":60,"strengths":["有上线经历"],"weaknesses":["无量化"]},
              {"name":"技能匹配","score":70,"strengths":["覆盖常用框架"],"weaknesses":["都是了解"]},
              {"name":"表达与量化","score":68,"strengths":["结构清晰"],"weaknesses":["无数字"]}
            ],"issues":[{"severity":"HIGH","quote":"参与开发","problem":"看不出你的贡献","suggestion":"写清你负责的部分"}],"summary":"典型应届简历"}""";

    private final ResumeDiagnosisService diagnosisService;
    private final ResumeDiagnosisMapper mapper;
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    /** 手写构造器：{@code @Qualifier} 必须落在构造参数上，Lombok 不会搬字段注解。 */
    public ResumeSmokeRunner(ResumeDiagnosisService diagnosisService,
                             ResumeDiagnosisMapper mapper,
                             @Qualifier("diagnosisChatModel") ChatModel chatModel,
                             ObjectMapper objectMapper) {
        this.diagnosisService = diagnosisService;
        this.mapper = mapper;
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        System.out.println("\n========== D11 简历诊断冒烟开始 ==========");

        // [1] 原始输出：验证 json_object 是否真的生效（看有没有 markdown 包裹、有没有寒暄）
        System.out.println("\n[1] 模型原始输出（判断 json_object 是否生效）");
        long rawBegin = System.currentTimeMillis();
        String raw = chatModel.chat(List.of(
                SystemMessage.from(ResumeDiagnosisPrompts.SYSTEM),
                UserMessage.from(ResumeDiagnosisPrompts.user(RESUME_WEAK))))
                .aiMessage().text();
        System.out.println("    耗时 " + (System.currentTimeMillis() - rawBegin) + " ms, 长度 "
                + raw.length() + " 字符");
        System.out.println("    首字符='" + raw.charAt(0) + "', 是否以 ``` 开头=" + raw.trim().startsWith("```"));
        System.out.println("    原文前 300 字:\n" + indent(preview(raw, 300)));

        // [2] 区分度对比：两份简历各跑一次全链路
        System.out.println("\n[2] 区分度对比（平庸 vs 优秀）");
        ResumeDiagnosis weak = diagnoseAndPrint("A 平庸", RESUME_WEAK);
        ResumeDiagnosis strong = diagnoseAndPrint("B 优秀", RESUME_STRONG);

        int gap = strong.getOverallScore() - weak.getOverallScore();
        System.out.println("\n    A=" + weak.getOverallScore() + "  B=" + strong.getOverallScore()
                + "  差值=" + gap);
        System.out.println(gap >= 10
                ? "    ✓ 优秀简历显著高于平庸简历，分数具备区分度"
                : "    ✗ 差值 " + gap + " 分不足 10，评分可能塌缩到同一个档位");

        // [3] 落库校验：不信内存对象，回查数据库
        System.out.println("\n[3] 落库回查");
        for (ResumeDiagnosis saved : List.of(weak, strong)) {
            ResumeDiagnosis fromDb = mapper.selectById(saved.getId());
            if (fromDb == null) {
                System.out.println("    ✗ 数据库里查不到 id=" + saved.getId());
            } else {
                System.out.println("    ✓ id=" + fromDb.getId() + ": 总分=" + fromDb.getOverallScore()
                        + ", model=" + fromDb.getModel()
                        + ", elapsedMs=" + fromDb.getElapsedMs()
                        + ", 原文长度=" + fromDb.getResumeText().length());
            }
        }
        List<ResumeDiagnosis> history = diagnosisService.history(1L);
        System.out.println("    ✓ history(userId=1) 返回 " + history.size() + " 条");

        // [4] 校验层负向测试：证明"脏输出不会被落库"，而不只是"好输出能落库"
        System.out.println("\n[4] 校验层负向测试");
        expectReject("纯文本非 JSON", "这是一份简历，我觉得还不错。");
        expectReject("维度少一个", """
                {"overallScore":70,"dimensions":[
                  {"name":"项目经历","score":70,"strengths":[],"weaknesses":[]},
                  {"name":"技能匹配","score":70,"strengths":[],"weaknesses":[]}
                ],"issues":[{"severity":"LOW","quote":"x","problem":"y","suggestion":"z"}],"summary":"s"}""");
        expectReject("分数越界", """
                {"overallScore":120,"dimensions":[
                  {"name":"项目经历","score":70,"strengths":[],"weaknesses":[]},
                  {"name":"技能匹配","score":70,"strengths":[],"weaknesses":[]},
                  {"name":"表达与量化","score":70,"strengths":[],"weaknesses":[]}
                ],"issues":[{"severity":"LOW","quote":"x","problem":"y","suggestion":"z"}],"summary":"s"}""");
        expectReject("未知维度名", """
                {"overallScore":70,"dimensions":[
                  {"name":"项目经历","score":70,"strengths":[],"weaknesses":[]},
                  {"name":"技能匹配","score":70,"strengths":[],"weaknesses":[]},
                  {"name":"颜值","score":70,"strengths":[],"weaknesses":[]}
                ],"issues":[{"severity":"LOW","quote":"x","problem":"y","suggestion":"z"}],"summary":"s"}""");
        expectReject("问题清单为空", """
                {"overallScore":70,"dimensions":[
                  {"name":"项目经历","score":70,"strengths":[],"weaknesses":[]},
                  {"name":"技能匹配","score":70,"strengths":[],"weaknesses":[]},
                  {"name":"表达与量化","score":70,"strengths":[],"weaknesses":[]}
                ],"issues":[],"summary":"s"}""");
        // 这一条针对 Jackson 的坑：int 字段缺失时默认填 0，不报错。
        // 没有 FAIL_ON_NULL_FOR_PRIMITIVES 的话，它会变成一份"合法"的 0 分诊断。
        expectReject("缺 overallScore（int 静默为 0 的坑）", """
                {"dimensions":[
                  {"name":"项目经历","score":70,"strengths":[],"weaknesses":[]},
                  {"name":"技能匹配","score":70,"strengths":[],"weaknesses":[]},
                  {"name":"表达与量化","score":70,"strengths":[],"weaknesses":[]}
                ],"issues":[{"severity":"LOW","quote":"x","problem":"y","suggestion":"z"}],"summary":"s"}""");
        expectReject("非法 severity", """
                {"overallScore":70,"dimensions":[
                  {"name":"项目经历","score":70,"strengths":[],"weaknesses":[]},
                  {"name":"技能匹配","score":70,"strengths":[],"weaknesses":[]},
                  {"name":"表达与量化","score":70,"strengths":[],"weaknesses":[]}
                ],"issues":[{"severity":"P0","quote":"x","problem":"y","suggestion":"z"}],"summary":"s"}""");
        expectReject("总评为空", """
                {"overallScore":70,"dimensions":[
                  {"name":"项目经历","score":70,"strengths":[],"weaknesses":[]},
                  {"name":"技能匹配","score":70,"strengths":[],"weaknesses":[]},
                  {"name":"表达与量化","score":70,"strengths":[],"weaknesses":[]}
                ],"issues":[{"severity":"LOW","quote":"x","problem":"y","suggestion":"z"}],"summary":""}""");
        // Set.of().contains(null) 会抛 NPE，不先判 null 的话这里会变成 500 而不是 409
        expectReject("维度名为 null", """
                {"overallScore":70,"dimensions":[
                  {"name":null,"score":70,"strengths":[],"weaknesses":[]},
                  {"name":"技能匹配","score":70,"strengths":[],"weaknesses":[]},
                  {"name":"表达与量化","score":70,"strengths":[],"weaknesses":[]}
                ],"issues":[{"severity":"LOW","quote":"x","problem":"y","suggestion":"z"}],"summary":"s"}""");

        // [5] 噪音容忍：模型偶尔包 ```json 代码块，必须能剥掉后正常解析
        System.out.println("\n[5] markdown 代码块噪音容忍");
        // 形态 A：```json 后换行
        var fenced = diagnosisService.parseAndValidate("```json\n" + SAMPLE_JSON + "\n```");
        System.out.println("    ✓ A 标记后换行, 总分=" + fenced.overallScore());
        // 形态 B：```json 后紧跟内容——按换行切会把首行内容切掉，这条专测该分支
        var inline = diagnosisService.parseAndValidate("```json" + SAMPLE_JSON + "```");
        System.out.println("    ✓ B 标记后紧跟内容, 总分=" + inline.overallScore());
        // 形态 C：压成真正的一行（没有任何换行）
        var oneLine = diagnosisService.parseAndValidate(
                "```" + SAMPLE_JSON.replace("\n", "").replace("\r", "") + "```");
        System.out.println("    ✓ C 压成单行, 总分=" + oneLine.overallScore());

        // [6] quote 逐字校验：提示词承诺"逐字摘自原文"，代码侧要能验证这件事
        System.out.println("\n[6] quote 逐字校验");
        int unverified = diagnosisService.warnIfQuoteNotVerbatim(fenced, RESUME_WEAK);
        System.out.println("    quote 未命中原文 " + unverified + " 条（0 表示全部逐字命中）");

        System.out.println("\n========== D11 简历诊断冒烟结束 ==========\n");
    }

    private ResumeDiagnosis diagnoseAndPrint(String label, String resume) {
        long begin = System.currentTimeMillis();
        ResumeDiagnosis saved = diagnosisService.diagnose(1L, resume);
        System.out.println("    [" + label + "] 总分=" + saved.getOverallScore()
                + ", 耗时=" + (System.currentTimeMillis() - begin) + "ms");
        System.out.println("      总评: " + saved.getSummary());
        System.out.println("      维度: " + dimensionScores(saved.getDimensions()));
        System.out.println("      问题数: " + countIssues(saved.getIssues())
                + ", 首条: " + preview(saved.getIssues(), 150));
        return saved;
    }

    /** 把库里的 dimensions JSON 压成 "项目经历=45 / 技能匹配=50 / 表达与量化=40"。 */
    private String dimensionScores(String json) {
        try {
            JsonNode arr = objectMapper.readTree(json);
            StringBuilder sb = new StringBuilder();
            for (JsonNode d : arr) {
                if (sb.length() > 0) {
                    sb.append(" / ");
                }
                sb.append(d.path("name").asText()).append('=').append(d.path("score").asInt());
            }
            return sb.toString();
        } catch (JsonProcessingException e) {
            return "（维度解析失败: " + e.getOriginalMessage() + "）";
        }
    }

    private int countIssues(String json) {
        try {
            return objectMapper.readTree(json).size();
        } catch (JsonProcessingException e) {
            return -1;
        }
    }

    private void expectReject(String label, String badJson) {
        try {
            diagnosisService.parseAndValidate(badJson);
            System.out.println("    ✗ " + label + "：本应被拒绝，却通过了校验");
        } catch (RuntimeException e) {
            System.out.println("    ✓ " + label + " → 已拒绝: " + preview(e.getMessage(), 90));
        }
    }

    private static String preview(String s, int max) {
        if (s == null) {
            return "null";
        }
        String oneLine = s.replaceAll("\\s+", " ");
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }

    private static String indent(String s) {
        return s.lines().map(l -> "      " + l).reduce("", (a, b) -> a + b + "\n");
    }
}
