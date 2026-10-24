package com.mockverse.monolith.controller;

import com.mockverse.monolith.dto.AnswerResponse;
import com.mockverse.monolith.dto.MessageView;
import com.mockverse.monolith.dto.StartResponse;
import com.mockverse.monolith.service.InterviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

/** 面试接口。 */
@Slf4j
@RestController
@RequestMapping("/api/interview")
@RequiredArgsConstructor
public class InterviewController {

    /** SSE 整轮上限: 覆盖流式超时(事件间隔 120s)之外的余量, 到点由容器强制断开。 */
    private static final long SSE_TIMEOUT_MILLIS = 180_000L;

    private final InterviewService interviewService;

    public record AnswerRequest(String answer) {}

    @PostMapping("/start")
    public StartResponse start(@RequestParam(defaultValue = "1") Long userId,
                               @RequestParam(required = false) Long questionId,
                               @RequestParam(required = false) String category,
                               @RequestParam(required = false) String difficulty) {
        return interviewService.start(userId, questionId, category, difficulty);
    }

    @PostMapping("/{sessionId}/answer")
    public AnswerResponse answer(@PathVariable Long sessionId, @RequestBody AnswerRequest request) {
        return interviewService.answer(sessionId, request.answer());
    }

    /**
     * 流式回答（D13）: 终端 {@code curl -N -X POST .../answer/stream} 逐字输出。
     *
     * <p>事件载荷是 {@link InterviewService.StreamEvent} 的 JSON 序列化:
     * chunk(增量文本, 带 author 归因) / done(整轮结束) / error(降级话术)。
     * 业务校验失败(400/409)在订阅前同步抛出, 仍走 ProblemDetail。
     */
    @PostMapping(value = "/{sessionId}/answer/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter answerStream(@PathVariable Long sessionId, @RequestBody AnswerRequest request) {
        // 校验同步抛出(订阅开始前), 所以这里拿到的流已保证会话合法
        var stream = interviewService.answerStream(sessionId, request.answer());
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);

        stream.subscribe(
                evt -> {
                    try {
                        emitter.send(SseEmitter.event().data(evt));
                    } catch (IOException e) {
                        // 客户端断开: 取消订阅即可, 服务端落库已在流的 defer/onError 钩子里安排
                        log.warn("SSE 客户端断开: session={}, {}", sessionId, e.getMessage());
                    }
                },
                error -> {
                    // 订阅前抛出的校验异常已由 answerStream 同步处理, 走到这里的是订阅期意外
                    try {
                        emitter.send(SseEmitter.event().data(
                                new InterviewService.ErrorEvent("链路异常: " + error.getMessage())));
                    } catch (IOException ignored) {
                        // 断开就断开
                    } finally {
                        emitter.complete();
                    }
                },
                emitter::complete);

        emitter.onTimeout(emitter::complete);
        return emitter;
    }

    @GetMapping("/{sessionId}/history")
    public List<MessageView> history(@PathVariable Long sessionId) {
        return interviewService.history(sessionId);
    }
}
