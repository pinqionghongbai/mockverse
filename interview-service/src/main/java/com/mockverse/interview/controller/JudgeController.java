package com.mockverse.interview.controller;

import com.mockverse.interview.service.JudgeSubmissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 异步判题接口（D20）。
 *
 * <p><b>与 D18 的 {@code judge_code} 的分工</b>：{@code judge_code} 是**面试官 Agent
 * 在对话里调用的工具**（同步，结果进模型上下文，由模型转述）；这里是**客户端直接提交
 * 代码**（异步，结果推给客户端，不经过模型）。两条链路共用 judge-service 的同一个
 * {@code JavaJudge}，所以同一个提交从哪条路进去，判出来的事实都一样。
 *
 * <p><b>用法</b>：
 * <ol>
 *   <li>{@code POST /{sessionId}/judge} → 立刻拿到 {@code submissionId} + {@code PENDING}</li>
 *   <li>{@code GET /{sessionId}/judge/stream}（SSE）→ 结果到达时收到一条
 *       {@code {submissionId, status, result}}</li>
 *   <li>SSE 不方便时用 {@code GET /{sessionId}/judge/{submissionId}} 轮询</li>
 * </ol>
 */
@Slf4j
@RestController
@RequestMapping("/api/interview")
@RequiredArgsConstructor
public class JudgeController {

    private final JudgeSubmissionService submissionService;

    public record JudgeSubmitRequest(String code, String stdin, String expectedOutput,
                                     Integer timeoutSeconds) {
    }

    /** 提交代码，立即返回提交 ID——**不等判题**。 */
    @PostMapping("/{sessionId}/judge")
    public JudgeSubmissionService.Submission submit(@PathVariable Long sessionId,
                                                    @RequestBody JudgeSubmitRequest request) {
        try {
            return submissionService.submit(sessionId, request.code(), request.stdin(),
                    request.expectedOutput(), request.timeoutSeconds());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** 轮询兜底：SSE 连不上或客户端只想查一次时用。 */
    @GetMapping("/{sessionId}/judge/{submissionId}")
    public JudgeSubmissionService.Submission get(@PathVariable Long sessionId,
                                                 @PathVariable String submissionId) {
        JudgeSubmissionService.Submission submission = submissionService.get(submissionId);
        if (submission == null || !submission.sessionId().equals(sessionId)) {
            // 会话不匹配也返回 404：提交 ID 是全局唯一的，跨会话查别人的提交不该给
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "提交不存在: " + submissionId);
        }
        return submission;
    }

    /** 结果流。同一会话可开多条连接，结果会推给所有连接。 */
    @GetMapping(value = "/{sessionId}/judge/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long sessionId) {
        return submissionService.openStream(sessionId);
    }
}
