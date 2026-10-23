package com.mockverse.monolith.smoke;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mockverse.monolith.agent.QuestionTools;
import com.mockverse.monolith.dto.AnswerResponse;
import com.mockverse.monolith.dto.MessageView;
import com.mockverse.monolith.dto.StartResponse;
import com.mockverse.monolith.entity.Question;
import com.mockverse.monolith.mapper.QuestionMapper;
import com.mockverse.monolith.service.InterviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * D9 验收: 命令行跑通一轮「出题 → 回答 → 追问」。
 *
 * <p>用法: {@code mvn spring-boot:run -Dspring-boot.run.profiles=interview}
 *
 * <p>回答用脚本写死（"数组+链表"这个半对半错的答案），目的是逼出一次**有信息量的追问**，
 * 而不是测模型会不会夸人。因此题目也必须钉死在同一道 HashMap 题上——否则随机抽到
 * "String 为什么不可变"，候选人却答 HashMap，追问会变成鸡同鸭讲（D9 实际踩过）。
 */
@Component
@Profile("interview")
@Order(10)
@RequiredArgsConstructor
public class InterviewSmokeRunner implements ApplicationRunner {

    private final InterviewService interviewService;
    private final QuestionMapper questionMapper;
    private final QuestionTools questionTools;

    @Override
    public void run(ApplicationArguments args) {
        System.out.println("\n========== D9 面试官 Agent 冒烟开始 ==========");

        Question q = questionMapper.selectOne(
                Wrappers.<Question>lambdaQuery()
                        .like(Question::getContent, "扩容机制")
                        .last("LIMIT 1"));
        if (q == null) {
            System.out.println("[interview] 题库里没有 HashMap 扩容题，跳过。");
            return;
        }

        StartResponse start = interviewService.start(1L, q.getId(), null, null);
        System.out.println("[1] 出题  题号=" + start.questionId() + " 分类=" + start.category());
        System.out.println("    题干: " + start.question());
        System.out.println("    面试官: " + start.agentMessage());

        String candidateAnswer = "HashMap 底层是数组加链表，元素多了会扩容，扩容就是把容量翻倍然后重新放一遍。";
        System.out.println("\n[2] 候选人回答: " + candidateAnswer);

        AnswerResponse answer = interviewService.answer(start.sessionId(), candidateAnswer);
        System.out.println("\n[3] 面试官追问: " + answer.agentMessage());
        System.out.println("    轮次=" + answer.round() + " 是否结束=" + answer.finished());

        System.out.println("\n[4] 会话历史落库检查:");
        for (MessageView m : interviewService.history(start.sessionId())) {
            System.out.printf("    seq=%d role=%-5s agent=%-4s %s%n",
                    m.seqNo(), m.role(), m.agentName() == null ? "-" : m.agentName(),
                    abbreviate(m.content()));
        }

        // [5] 工具直接调用: 主链路刻意不走工具(抽题在 Java 侧保证确定性), 但工具本身必须可用,
        //     否则 D12 的"换一题"按钮一接就炸。这里绕开 LLM 直接调, 验证返回结构与键名。
        Map<String, Object> picked = questionTools.pickQuestion("JAVA_BASIC", null);
        System.out.println("\n[5] pick_question 工具直接调用:");
        System.out.println("    found=" + picked.get("found") + " category=" + picked.get("category")
                + " difficulty=" + picked.get("difficulty"));
        System.out.println("    content=" + abbreviate(String.valueOf(picked.get("content"))));
        System.out.println("    键集合=" + picked.keySet());

        System.out.println("========== D9 冒烟结束 ==========\n");
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String oneLine = s.replaceAll("\\s+", " ");
        return oneLine.length() <= 80 ? oneLine : oneLine.substring(0, 80) + "…";
    }
}
