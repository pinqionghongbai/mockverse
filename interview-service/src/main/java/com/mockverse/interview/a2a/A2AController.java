package com.mockverse.interview.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.a2a.server.requesthandlers.DefaultRequestHandler;
import io.a2a.spec.InvalidParamsError;
import io.a2a.spec.JSONRPCError;
import io.a2a.spec.MessageSendParams;
import io.a2a.spec.StreamingEventKind;
import io.a2a.spec.TaskIdParams;
import io.a2a.spec.TaskQueryParams;
import io.a2a.util.Utils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A2A 传输层：AgentCard 发现 + JSON-RPC 端点（D16）。
 *
 * <p><b>职责边界</b>：本类只做"HTTP/JSON-RPC 信封 ↔ spec 对象"的翻译，业务状态机在
 * a2a SDK 的 {@link DefaultRequestHandler} 里。这样 D17 的 RemoteA2AAgent（按 A2A 规范
 * 发报文）与我们是同一套协议，而不是自造的私有接口。
 *
 * <p><b>为什么自己写而不是引官方 webservice 模块</b>：见 {@link A2AServerConfig} 的类注释——
 * ADK 1.13.0 里那个模块不存在。
 *
 * <p><b>序列化用 {@link Utils#OBJECT_MAPPER} 而不是 Spring 的 ObjectMapper</b>：
 * spec 记录类上的 Jackson 注解（多态 part、kind 判别器）是按 SDK 的 mapper 配置设计的，
 * 换一个 mapper 容易漏模块。返回值直接给 String，避免 Spring 二次序列化。
 */
@Slf4j
@RestController
public class A2AController {

    private final DefaultRequestHandler requestHandler;
    private final io.a2a.spec.AgentCard agentCard;

    /**
     * 构造器手工写而不是用 {@code @RequiredArgsConstructor}：handler 来自
     * {@link A2AServerConfig.A2AHandler} 这层包装（原因见那边的注释），
     * 需要先拆包再赋值，Lombok 生成不了。
     */
    public A2AController(A2AServerConfig.A2AHandler handler, io.a2a.spec.AgentCard agentCard) {
        this.requestHandler = handler.requestHandler();
        this.agentCard = agentCard;
    }

    /** A2A 规范的服务发现端点：远端靠它拿到 url / skills / capabilities。 */
    @GetMapping(value = A2AServerConfig.AGENT_CARD_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public String agentCard() {
        return toJson(agentCard);
    }

    /**
     * JSON-RPC 主端点。
     *
     * <p>支持 A2A 0.3.x 的四个核心方法。**流式走独立路径**（{@code /a2a/v1/stream}）：
     * JSON-RPC 的流式响应是 SSE，和一次性响应不能共用同一个 handler。
     */
    @PostMapping(value = A2AServerConfig.RPC_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> rpc(@RequestBody JsonNode body) {
        JsonNode id = body.get("id");
        String method = body.path("method").asText("");
        JsonNode params = body.path("params");
        try {
            Object result = switch (method) {
                case "message/send" -> requestHandler.onMessageSend(
                        parse(params, MessageSendParams.class), null);
                case "tasks/get" -> requestHandler.onGetTask(
                        parse(params, TaskQueryParams.class), null);
                case "tasks/cancel" -> requestHandler.onCancelTask(
                        parse(params, TaskIdParams.class), null);
                default -> throw new InvalidParamsError("不支持的方法: " + method);
            };
            return ResponseEntity.ok(okResponse(id, result));
        } catch (JSONRPCError e) {
            // 协议层错误：JSON-RPC 规定用 200 + error 对象返回，不是 HTTP 5xx
            return ResponseEntity.ok(errorResponse(id, e));
        } catch (Exception e) {
            // 细节只进日志，不回给对端：这个端点可被网络访问，异常文本会泄露内部路径/主机名
            log.error("A2A 请求处理失败: method={}", method, e);
            return ResponseEntity.ok(errorResponse(id, new io.a2a.spec.InternalError("服务内部错误")));
        }
    }

    /**
     * 流式端点（SSE）。
     *
     * <p>每个事件是一行 {@code data:}，内容是完整的 JSON-RPC 响应（同 id），
     * 与 A2A 规范的 message/stream 语义一致。超时给 3 分钟——面试一轮的 LLM 调用
     * 实测 3~5 秒，留足余量；超时后 emitter 关闭，客户端会收到连接结束。
     */
    @PostMapping(value = A2AServerConfig.RPC_PATH + "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody JsonNode body) {
        JsonNode id = body.get("id");
        SseEmitter emitter = new SseEmitter(180_000L);
        AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();

        try {
            MessageSendParams params = parse(body.path("params"), MessageSendParams.class);
            requestHandler.onMessageSendStream(params, null).subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription s) {
                    subscription.set(s);
                    s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(StreamingEventKind event) {
                    try {
                        emitter.send(SseEmitter.event().data(okResponse(id, event)));
                    } catch (IOException | IllegalStateException e) {
                        // 客户端断开或 emitter 已关闭：取消上游，避免继续消耗模型调用
                        cancelUpstream();
                        emitter.complete();
                    }
                }

                @Override
                public void onError(Throwable t) {
                    log.warn("A2A 流式处理失败", t);
                    emitter.completeWithError(t);
                }

                @Override
                public void onComplete() {
                    emitter.complete();
                }

                private void cancelUpstream() {
                    Flow.Subscription s = subscription.get();
                    if (s != null) {
                        s.cancel();
                    }
                }
            });
        } catch (JSONRPCError e) {
            // JSONRPCError 继承 java.lang.Error 而不是 Exception，必须单独接：否则会穿透
            // 下面的 catch (Exception) 直接冒泡给 Spring，客户端拿到的就不是 JSON-RPC 错误体。
            log.warn("A2A 流式请求被拒绝: {}", e.getMessage());
            failQuietly(emitter, id, e);
        } catch (Exception e) {
            log.error("A2A 流式请求解析失败", e);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /** 在 SSE 通道上回一个 JSON-RPC error 事件再关闭——比直接断开更便于对端定位。 */
    private static void failQuietly(SseEmitter emitter, JsonNode id, JSONRPCError error) {
        try {
            emitter.send(SseEmitter.event().data(errorResponse(id, error)));
            emitter.complete();
        } catch (Exception sendFailure) {
            emitter.completeWithError(sendFailure);
        }
    }

    /** params 解析失败按 JSON-RPC 规范归为 -32602（InvalidParams），而不是内部错误。 */
    private static <T> T parse(JsonNode params, Class<T> type) {
        try {
            return Utils.OBJECT_MAPPER.treeToValue(params, type);
        } catch (Exception e) {
            throw new InvalidParamsError("参数格式错误: " + e.getMessage());
        }
    }

    private static String okResponse(JsonNode id, Object result) {
        ObjectNode root = Utils.OBJECT_MAPPER.createObjectNode();
        root.put("jsonrpc", "2.0");
        root.set("id", id == null ? Utils.OBJECT_MAPPER.nullNode() : id);
        root.set("result", Utils.OBJECT_MAPPER.valueToTree(result));
        return toJson(root);
    }

    private static String errorResponse(JsonNode id, JSONRPCError error) {
        ObjectNode root = Utils.OBJECT_MAPPER.createObjectNode();
        root.put("jsonrpc", "2.0");
        root.set("id", id == null ? Utils.OBJECT_MAPPER.nullNode() : id);
        root.set("error", Utils.OBJECT_MAPPER.valueToTree(error));
        return toJson(root);
    }

    /**
     * 序列化失败说明 spec 对象的 Jackson 配置被破坏了，属于不可恢复的内部错误——
     * 包成 RuntimeException 让调用方（或 Spring）统一按 500 处理，而不是在每条路径上
     * 传播受检的 IOException。
     */
    private static String toJson(Object value) {
        try {
            return Utils.OBJECT_MAPPER.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("A2A 报文序列化失败", e);
        }
    }
}
