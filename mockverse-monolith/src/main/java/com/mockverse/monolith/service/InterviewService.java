package com.mockverse.monolith.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.google.adk.agents.LlmAgent;
import com.google.adk.runner.Runner;
import com.mockverse.monolith.agent.AgentRuntime;
import com.mockverse.monolith.agent.InterviewerAgentFactory;
import com.mockverse.monolith.agent.InterviewerPrompts;
import com.mockverse.monolith.config.InterviewProperties;
import com.mockverse.monolith.dto.AnswerResponse;
import com.mockverse.monolith.dto.MessageView;
import com.mockverse.monolith.dto.StartResponse;
import com.mockverse.monolith.entity.ChatMessage;
import com.mockverse.monolith.entity.InterviewSession;
import com.mockverse.monolith.entity.Question;
import com.mockverse.monolith.mapper.ChatMessageMapper;
import com.mockverse.monolith.mapper.InterviewSessionMapper;
import com.mockverse.monolith.mapper.QuestionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 面试主流程: 开会话 → 出题 → 收回答 → 追问。
 *
 * <p><b>职责边界</b>: 抽题和落库由本服务负责（确定性、可测试），LLM 只负责"怎么问"。
 * 这样即使模型跑偏，题库与会话数据也不会脏。Agent 侧的 {@code pick_question} 工具
 * 只用于面试中途"换一题"。
 *
 * <p><b>事务边界</b>: 刻意不用方法级 {@code @Transactional}。一次 DeepSeek 往返是秒级的，
 * 若把它圈进事务，连接会被独占 2-4 秒，并发面试一多就耗尽 Hikari 池。这里用
 * {@link TransactionTemplate} 只包住"纯 DB 写"的那几行，LLM 调用在事务之外。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewService {

    private static final String AGENT_NAME = "面试官";

    private final InterviewSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final QuestionMapper questionMapper;
    private final InterviewerAgentFactory agentFactory;
    private final AgentRuntime runtime;
    private final InterviewProperties properties;
    private final TransactionTemplate tx;

    public StartResponse start(Long userId, Long questionId, String category, String difficulty) {
        Question question = questionId == null
                ? pickQuestion(category, difficulty)
                : pickQuestionById(questionId);

        // 先预生成会话 ID: adkSessionId 依赖它，而 LLM 调用必须在事务之外
        long sessionId = IdWorker.getId();
        String adkSessionId = "iv-" + sessionId;
        runtime.ensureSession(String.valueOf(userId), adkSessionId);

        String reply = chat(userId, adkSessionId,
                InterviewerPrompts.opening(question.getContent(), question.getReferenceAnswer()));

        tx.executeWithoutResult(status -> {
            InterviewSession session = new InterviewSession();
            session.setId(sessionId);
            session.setUserId(userId);
            session.setTitle("模拟面试 · " + question.getCategory());
            session.setStatus("IN_PROGRESS");
            session.setInterviewType("JAVA_BACKEND");
            session.setTotalRounds(0);
            session.setMaxRounds(properties.getMaxRounds());
            session.setStartedAt(LocalDateTime.now());
            sessionMapper.insert(session);
            appendMessage(sessionId, "AGENT", AGENT_NAME, reply, question.getId());
        });

        return new StartResponse(
                sessionId, adkSessionId, question.getId(),
                question.getCategory(), question.getDifficulty(),
                question.getContent(), reply);
    }

    public AnswerResponse answer(Long sessionId, String userAnswer) {
        if (userAnswer == null || userAnswer.isBlank()) {
            // content 列是 NOT NULL，空回答直接放行会变成 DataIntegrityViolation（500），提前拦成 400
            throw new IllegalArgumentException("回答不能为空");
        }
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在: " + sessionId);
        }
        if (!"IN_PROGRESS".equals(session.getStatus())) {
            throw new IllegalStateException("会话已结束: " + session.getStatus());
        }

        int round = (session.getTotalRounds() == null ? 0 : session.getTotalRounds()) + 1;
        boolean finished = round >= session.getMaxRounds();

        String adkSessionId = "iv-" + sessionId;
        runtime.ensureSession(String.valueOf(session.getUserId()), adkSessionId);

        // 先拿模型回复再落库: 模型失败时用户消息不会半截入库，重试也不会产生重复回答
        String reply = chat(session.getUserId(), adkSessionId,
                InterviewerPrompts.answerTurn(userAnswer, finished));

        tx.executeWithoutResult(status -> {
            appendMessage(sessionId, "USER", null, userAnswer, null);
            appendMessage(sessionId, "AGENT", AGENT_NAME, reply, null);
            session.setTotalRounds(round);
            if (finished) {
                session.setStatus("COMPLETED");
                session.setEndedAt(LocalDateTime.now());
            }
            sessionMapper.updateById(session);
        });

        return new AnswerResponse(sessionId, round, reply, finished);
    }

    public List<MessageView> history(Long sessionId) {
        return messageMapper.selectList(
                        Wrappers.<ChatMessage>lambdaQuery()
                                .eq(ChatMessage::getSessionId, sessionId)
                                .orderByAsc(ChatMessage::getSeqNo))
                .stream()
                .map(m -> new MessageView(m.getSeqNo(), m.getRole(), m.getAgentName(),
                        m.getContent(), m.getCreatedAt()))
                .toList();
    }

    // ------------------------------------------------------------------

    /**
     * 抽题。先按条件取候选集再在内存里随机，避免 {@code ORDER BY RAND()} 全表扫描；
     * 条件列顺序与 {@code idx_cat_diff_enabled} 一致，能命中联合索引。
     */
    private Question pickQuestion(String category, String difficulty) {
        List<Question> candidates = questionMapper.selectList(
                Wrappers.<Question>lambdaQuery()
                        .eq(category != null && !category.isBlank(), Question::getCategory, category)
                        .eq(difficulty != null && !difficulty.isBlank(), Question::getDifficulty, difficulty)
                        .eq(Question::getEnabled, true));
        if (candidates.isEmpty()) {
            throw new IllegalStateException("题库为空或没有符合条件的题目，请先导入题库。");
        }
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    /** 指定题目开面（"重做此题"/冒烟固定题目用）。 */
    private Question pickQuestionById(Long questionId) {
        Question question = questionMapper.selectById(questionId);
        if (question == null || !Boolean.TRUE.equals(question.getEnabled())) {
            throw new IllegalArgumentException("题目不存在或已停用: " + questionId);
        }
        return question;
    }

    private String chat(Long userId, String adkSessionId, String userText) {
        LlmAgent agent = agentFactory.create();
        Runner runner = runtime.runnerFor(agent);
        long begin = System.currentTimeMillis();
        String reply = runtime.chat(runner, String.valueOf(userId), adkSessionId, userText);
        log.info("Agent 回复完成, 耗时 {} ms, 长度 {} 字", System.currentTimeMillis() - begin, reply.length());
        if (reply.isBlank()) {
            throw new IllegalStateException("模型返回为空，请检查 DeepSeek 连通性。");
        }
        return reply;
    }

    private void appendMessage(Long sessionId, String role, String agentName, String content, Long questionId) {
        ChatMessage msg = new ChatMessage();
        msg.setSessionId(sessionId);
        msg.setRole(role);
        msg.setAgentName(agentName);
        msg.setContent(content);
        msg.setQuestionId(questionId);
        msg.setSeqNo(nextSeq(sessionId));
        msg.setTokenCount(content == null ? 0 : content.length());
        messageMapper.insert(msg);
    }

    /** 会话内序号从 1 递增；由 uk_session_seq 唯一索引兜底防并发重复。 */
    private int nextSeq(Long sessionId) {
        ChatMessage last = messageMapper.selectOne(
                Wrappers.<ChatMessage>lambdaQuery()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByDesc(ChatMessage::getSeqNo)
                        .last("LIMIT 1"));
        return last == null ? 1 : last.getSeqNo() + 1;
    }
}
