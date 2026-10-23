package com.mockverse.monolith.smoke;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.google.adk.agents.LlmAgent;
import com.google.adk.runner.Runner;
import com.mockverse.monolith.agent.AgentRuntime;
import com.mockverse.monolith.agent.InterviewerAgentFactory;
import com.mockverse.monolith.agent.InterviewerPrompts;
import com.mockverse.monolith.entity.Question;
import com.mockverse.monolith.mapper.QuestionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * D9 下午: 提示词 A-B-C 对照。
 *
 * <p>单变量控制: 同一道题、同一段候选人回答、同一模型、temperature 相同，只换 instruction。
 * 用法: {@code mvn spring-boot:run -Dspring-boot.run.profiles=prompt-ab}
 */
@Component
@Profile("prompt-ab")
@Order(10)
@RequiredArgsConstructor
public class PromptAbRunner implements ApplicationRunner {

    private static final String CANDIDATE_ANSWER =
            "HashMap 底层是数组加链表，元素多了会扩容，扩容就是把容量翻倍然后重新放一遍。";

    private final InterviewerAgentFactory agentFactory;
    private final AgentRuntime runtime;
    private final QuestionMapper questionMapper;

    @Override
    public void run(ApplicationArguments args) {
        Question q = questionMapper.selectOne(
                Wrappers.<Question>lambdaQuery()
                        .like(Question::getContent, "扩容机制")
                        .last("LIMIT 1"));
        if (q == null) {
            System.out.println("[prompt-ab] 题库里没有 HashMap 扩容题，跳过。");
            return;
        }

        String opening = InterviewerPrompts.opening(q.getContent(), q.getReferenceAnswer());

        System.out.println("\n########## D9 提示词 A-B-C 对照 ##########");
        System.out.println("题目: " + q.getContent());
        System.out.println("回答: " + CANDIDATE_ANSWER);

        for (String version : List.of("v1", "v2", "v3")) {
            String userId = "ab-user-" + version;
            String sessionId = UUID.randomUUID().toString();
            LlmAgent agent = agentFactory.create(version);
            Runner runner = runtime.runnerFor(agent);
            runtime.ensureSession(userId, sessionId);

            long begin = System.currentTimeMillis();
            String first = runtime.chat(runner, userId, sessionId, opening);
            String second = runtime.chat(runner, userId, sessionId,
                    InterviewerPrompts.answerTurn(CANDIDATE_ANSWER, false));
            long cost = System.currentTimeMillis() - begin;

            System.out.println("\n----- " + version.toUpperCase() + " (耗时 " + cost + " ms) -----");
            System.out.println("[出题] " + first);
            System.out.println("[追问] " + second);
        }

        System.out.println("\n########## 对照结束 ##########\n");
    }
}
