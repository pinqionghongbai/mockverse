package com.mockverse.monolith.smoke;

import com.google.adk.agents.LlmAgent;
import com.google.adk.runner.Runner;
import com.mockverse.monolith.agent.AgentRuntime;
import com.mockverse.monolith.agent.AgentRuntime.ChatResult;
import com.mockverse.monolith.agent.MultiAgentFactory;
import com.mockverse.monolith.dto.StartResponse;
import com.mockverse.monolith.rag.KnowledgeService;
import com.mockverse.monolith.service.InterviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * D12 验收: 多 Agent 进程内编排 + 异常兜底。
 *
 * <p>用法: {@code mvn spring-boot:run -Dspring-boot.run.profiles=multiagent}
 * （需 MySQL + pgvector 两个容器都起着）
 *
 * <p>三个场景对应执行手册的验收标准:
 * <ol>
 *   <li><b>各司其职</b>: 同一个 ADK 会话里发三类意图的消息，靠 Event.author 归因
 *       验证各自路由到正确子 Agent——"跑通了"不算数，author 可查才算</li>
 *   <li><b>故障兜底</b>: 把 knowledge_agent 的模型换成必抛异常的桩，发知识问题
 *       → 主流程拿到降级结果不崩；随后走真实面试主流程（正常树）照常出题</li>
 *   <li><b>超时兜底</b>: 换成睡眠 8s 的桩 + 3s 超时 → Reactive timeout 触发，
 *       同样降级不崩</li>
 * </ol>
 */
@Component
@Profile("multiagent")
@Order(10)
@RequiredArgsConstructor
public class MultiAgentSmokeRunner implements ApplicationRunner {

    /** 冒烟专用 userId（区别于主流程的 1，落库数据可识别、可清理）。 */
    private static final String SMOKE_USER = "912";
    private static final String SMOKE_SESSION = "multiagent-smoke";

    private static final String RESUME_TEXT =
            "本人熟悉 Java 基础，了解集合框架和多线程，做过课程设计的图书管理系统，"
                    + "会用 MySQL 增删改查，了解 Redis 缓存，无实习经历。";

    private final MultiAgentFactory multiAgentFactory;
    private final AgentRuntime runtime;
    private final KnowledgeService knowledgeService;
    private final InterviewService interviewService;

    @Override
    public void run(ApplicationArguments args) {
        System.out.println("\n========== D12 多 Agent 编排冒烟开始 ==========");
        knowledgeService.ensureIndexed();

        int passed = 0;
        int failed = 0;

        // 场景 1: 一次会话，三类意图，三个子 Agent 各司其职
        boolean s1 = scenario1Routing();
        // 场景 2: 断掉 knowledge_agent（fail 桩），主流程不崩
        boolean s2 = scenario2FaultTolerance();
        // 场景 3: knowledge_agent 无限睡眠（slow 桩），短超时触发 Reactive timeout
        boolean s3 = scenario3Timeout();
        passed += (s1 ? 1 : 0) + (s2 ? 1 : 0) + (s3 ? 1 : 0);
        failed += (s1 ? 0 : 1) + (s2 ? 0 : 1) + (s3 ? 0 : 1);

        System.out.println("\n========== D12 冒烟结束: " + passed + " 通过 / " + failed + " 失败 ==========\n");
    }

    // ------------------------------------------------------------------

    private boolean scenario1Routing() {
        System.out.println("\n----- 场景 1: 三类意图各司其职（同一 ADK 会话）-----");
        LlmAgent root = multiAgentFactory.create();
        Runner runner = runtime.runnerFor(root);
        runtime.ensureSession(SMOKE_USER, SMOKE_SESSION);

        boolean knowledgeOk = routeAndCheck(runner, "知识问答",
                "HashMap 的扩容机制是什么？什么时候会触发扩容？",
                MultiAgentFactory.KNOWLEDGE);
        boolean resumeOk = routeAndCheck(runner, "简历诊断",
                "请帮我诊断这份简历：" + RESUME_TEXT,
                MultiAgentFactory.RESUME);

        // 第三类意图走真实主流程（InterviewService 内部就是这棵 root 树）
        boolean interviewOk;
        try {
            StartResponse start = interviewService.start(1L, null, "JAVA_BASIC", null);
            System.out.println("[面试] 出题成功: 会话=" + start.sessionId()
                    + " 题号=" + start.questionId());
            System.out.println("    面试官: " + abbreviate(start.agentMessage()));
            // 出题即证明 coordinator 把开场消息路由给了 interviewer_agent（否则不会呈现题目）
            interviewOk = start.agentMessage() != null && !start.agentMessage().isBlank();
        } catch (Exception e) {
            System.out.println("[面试] ✗ 主流程异常: " + e.getMessage());
            interviewOk = false;
        }

        System.out.println("场景 1 结论: 知识=" + (knowledgeOk ? "✅" : "✗")
                + " 简历=" + (resumeOk ? "✅" : "✗")
                + " 面试=" + (interviewOk ? "✅" : "✗"));
        return knowledgeOk && resumeOk && interviewOk;
    }

    private boolean routeAndCheck(Runner runner, String label, String message, String expectedAuthor) {
        System.out.println("\n[" + label + "] 用户: " + abbreviate(message));
        ChatResult result = runtime.converse(runner, SMOKE_USER, SMOKE_SESSION,
                message, Duration.ofSeconds(120));
        printResult(result);
        Set<String> authors = result.turns().stream()
                .map(AgentRuntime.Turn::author).collect(Collectors.toSet());
        boolean ok = authors.contains(expectedAuthor);
        System.out.println("[" + label + "] 路由" + (ok ? "✅" : "✗")
                + " 期望含 author=" + expectedAuthor + "，实际=" + authors);
        return ok;
    }

    private boolean scenario2FaultTolerance() {
        System.out.println("\n----- 场景 2: 断掉 knowledge_agent（模型必抛异常），主流程不崩 -----");
        LlmAgent brokenRoot = multiAgentFactory.create(MultiAgentFactory.KNOWLEDGE, 0);
        Runner brokenRunner = runtime.runnerFor(brokenRoot);
        runtime.ensureSession(SMOKE_USER, SMOKE_SESSION + "-fault");

        System.out.println("[知识问答·故障树] 用户: HashMap 扩容后链表会怎样？");
        ChatResult result = runtime.converse(brokenRunner, SMOKE_USER, SMOKE_SESSION + "-fault",
                "HashMap 扩容后链表会怎样？", Duration.ofSeconds(60));
        printResult(result);
        boolean degraded = !result.ok() || result.turns().isEmpty();
        System.out.println("[知识问答·故障树] 降级" + (degraded ? "✅" : "✗")
                + "（error=" + result.error() + "）");

        // 故障只影响知识链路: 面试主流程（正常树）必须照常走完
        boolean mainFlowOk;
        try {
            StartResponse start = interviewService.start(1L, null, "JAVA_BASIC", null);
            System.out.println("[面试·主流程] 出题成功✅ 题号=" + start.questionId()
                    + " 回复: " + abbreviate(start.agentMessage()));
            mainFlowOk = start.agentMessage() != null && !start.agentMessage().isBlank();
        } catch (Exception e) {
            System.out.println("[面试·主流程] ✗ 异常: " + e.getMessage());
            mainFlowOk = false;
        }
        System.out.println("场景 2 结论: 降级=" + (degraded ? "✅" : "✗")
                + " 主流程不崩=" + (mainFlowOk ? "✅" : "✗"));
        return degraded && mainFlowOk;
    }

    private boolean scenario3Timeout() {
        System.out.println("\n----- 场景 3: knowledge_agent 睡眠 8s + 超时 3s → Reactive timeout -----");
        LlmAgent slowRoot = multiAgentFactory.create(MultiAgentFactory.KNOWLEDGE, 8000);
        Runner slowRunner = runtime.runnerFor(slowRoot);
        runtime.ensureSession(SMOKE_USER, SMOKE_SESSION + "-slow");

        long begin = System.currentTimeMillis();
        ChatResult result = runtime.converse(slowRunner, SMOKE_USER, SMOKE_SESSION + "-slow",
                "什么是索引的最左前缀？", Duration.ofSeconds(3));
        long elapsed = System.currentTimeMillis() - begin;
        printResult(result);
        boolean timedOut = !result.ok() && result.error() != null && result.error().contains("Timeout");
        System.out.println("[超时兜底] " + (timedOut ? "✅" : "✗")
                + " 实际等待 " + elapsed + " ms（应≈3s 而非 8s）, error=" + result.error());
        return timedOut;
    }

    // ------------------------------------------------------------------

    private static void printResult(ChatResult result) {
        if (result.turns().isEmpty()) {
            System.out.println("    （无文本输出） error=" + result.error());
            return;
        }
        for (AgentRuntime.Turn t : result.turns()) {
            System.out.println("    [" + t.author() + "] " + abbreviate(t.text()));
        }
        if (result.error() != null) {
            System.out.println("    （附错误标记: " + result.error() + "）");
        }
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String oneLine = s.replaceAll("\\s+", " ");
        return oneLine.length() <= 100 ? oneLine : oneLine.substring(0, 100) + "…";
    }
}
