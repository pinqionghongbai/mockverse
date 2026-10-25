package com.mockverse.interview.controller;

import com.mockverse.interview.dto.AnswerResponse;
import com.mockverse.interview.dto.MessageView;
import com.mockverse.interview.dto.StartResponse;
import com.mockverse.interview.service.InterviewService;
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
     * chunk(增量文本, 带 author 归因) / tool(正在调用哪个工具, D19 的"判题中…"依据) /
     * done(整轮结束) / error(降级话术)。
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
                        // 客户端断开: 不取消订阅, 让流走完, defer 里的落库照常执行
                        log.warn("SSE 客户端断开: session={}, {}", sessionId, e.getMessage());
                    } catch (Exception e) {
                        // 二查修复: emitter 在 complete/timeout(180s 整轮上限)后再 send 抛的是
                        // IllegalStateException。若放它传播, RxJava 会 dispose 上游,
                        // concatWith(defer) 里的落库永不执行——用户消息+轮数凭空丢失,
                        // 恰好违反"历史必须与用户所见一致"。吞掉发送失败, 落库不受影响。
                        log.warn("SSE 发送失败(emitter 已关闭?), 落库不受影响: session={}, {}",
                                sessionId, e.getMessage());
                    }
                },
                error -> {
                    // 订阅前抛出的校验异常已由 answerStream 同步处理, 走到这里的是订阅期意外
                    // (如 defer 落库阶段 DB 失败)。三查修复: catch 放宽为 Exception——
                    // emitter 已被 180s 超时 complete 时 send 抛 IllegalStateException,
                    // 从 onError 消费者向外传播会被 RxJavaPlugins 抛到调度线程(ADK 线程池
                    // 有被打死的风险)。与二查 Bug B 同族, 二查只修了 onNext 漏了这里。
                    try {
                        emitter.send(SseEmitter.event().data(
                                new InterviewService.ErrorEvent("链路异常: " + error.getMessage())));
                    } catch (Exception e) {
                        // emitter 已关闭(超时/完成), 发不出去就算了
                        log.warn("SSE 错误事件发送失败(emitter 已关闭?): session={}, {}",
                                sessionId, e.getMessage());
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
