package com.mockverse.knowledge.a2a;

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
 * <p><b>与 interview-service 的同名文件是同一份传输层</b>，只有包名不同。三份拷贝是
 * 有意的（服务独立可构建），代价是改一处必须同步三处，清单见
 * {@link A2AServerConfig} 的类注释。
 *
 * <p><b>所有方法共用 AgentCard.url 一个端点</b>（D16 六查修正）：A2A 的方法名在 JSON-RPC
 * 信封里，不在路径上。a2a SDK 的 {@code JSONRPCTransport} 实测就是这么做——{@code message/send}
 * 与 {@code message/stream} 都 POST 到 {@code agentCard.url()}，只有 {@code method} 字段不同
 * （前者收一次性 JSON，后者收 SSE）。D16 最初把流式放在 {@code /a2a/v1/stream} 子路径上，
 * 那是规范外的私有约定：任何合规客户端都调不到，而卡片里又写着 {@code streaming: true}，
 * 等于对 D17 的 RemoteA2AAgent 虚报能力。
 *
 * <p><b>序列化用 {@link Utils#OBJECT_MAPPER} 而不是 Spring 的 ObjectMapper</b>：
 * spec 记录类上的 Jackson 注解（多态 part、kind 判别器）是按 SDK 的 mapper 配置设计的，
 * 换一个 mapper 容易漏模块。返回值直接给 String，避免 Spring 二次序列化。
 */
@Slf4j
@RestController
public class A2AController {

    private static final String METHOD_SEND = "message/send";
    private static final String METHOD_STREAM = "message/stream";

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
     * <p><b>返回类型是 {@code Object}</b>：{@code message/stream} 回 {@link SseEmitter}，
     * 其余方法回 {@link ResponseEntity}。Spring 按返回值的**实际**类型选处理器
     * （{@code ServletInvocableHandlerMethod.getReturnValueType} 会把声明的 Object
     * 换成实际类），所以这里不需要拆成两个 handler——同路径的 POST 也无法按 body 里的
     * 方法名拆分。
     *
     * <p>不能加 {@code produces}：SSE 响应是 {@code text/event-stream}，与 JSON 互斥。
     * JSON 分支显式写 {@code contentType}，避免被协商成 text/plain。
     */
    @PostMapping(A2AServerConfig.RPC_PATH)
    public Object rpc(@RequestBody JsonNode body) {
        JsonNode id = body.get("id");
        String method = body.path("method").asText("");
        JsonNode params = body.path("params");

        if (METHOD_STREAM.equals(method)) {
            return stream(id, params);
        }

        try {
            Object result = switch (method) {
                case METHOD_SEND -> requestHandler.onMessageSend(
                        parse(params, MessageSendParams.class), null);
                case "tasks/get" -> requestHandler.onGetTask(
                        parse(params, TaskQueryParams.class), null);
                case "tasks/cancel" -> requestHandler.onCancelTask(
                        parse(params, TaskIdParams.class), null);
                default -> throw new InvalidParamsError("不支持的方法: " + method);
            };
            return json(okResponse(id, result));
        } catch (JSONRPCError e) {
            // 协议层错误：JSON-RPC 规定用 200 + error 对象返回，不是 HTTP 5xx
            return json(errorResponse(id, e));
        } catch (Exception e) {
            // 细节只进日志，不回给对端：这个端点可被网络访问，异常文本会泄露内部路径/主机名
            log.error("A2A 请求处理失败: method={}", method, e);
            return json(errorResponse(id, new io.a2a.spec.InternalError("服务内部错误")));
        }
    }

    /**
     * {@code message/stream} 的 SSE 实现。
     *
     * <p>每个事件是一行 {@code data:}，内容是完整的 JSON-RPC 响应（同 id），
     * 与 A2A 规范一致。超时给 3 分钟——面试一轮的 LLM 调用实测 3~5 秒，留足余量；
     * 超时后 emitter 关闭，客户端会收到连接结束。
     *
     * <p>解析阶段的错误（-32602）也要走 SSE 回一个 error 事件再关闭：客户端的 SSE
     * 监听器只认 {@code data:} 行，直接断开会让它拿不到原因。
     */
    private SseEmitter stream(JsonNode id, JsonNode params) {
        SseEmitter emitter = new SseEmitter(180_000L);
        AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();

        // 客户端断开 / 超时 / 出错时主动取消上游（D16 七查补）。
        // 不注册这三个回调的话，只有等到**下一次** emitter.send() 失败才会发现对端走了——
        // 若模型迟迟不出第一个事件（30s 的 agentCompletionTimeout 之内），这段时间一直占着
        // 一次模型调用在烧 token。cancel() 是幂等的，正常完成时再触发一次也无害。
        Runnable cancelUpstream = () -> {
            Flow.Subscription s = subscription.get();
            if (s != null) {
                s.cancel();
            }
        };
        emitter.onCompletion(cancelUpstream);
        emitter.onTimeout(cancelUpstream);
        emitter.onError(t -> cancelUpstream.run());

        try {
            MessageSendParams sendParams = parse(params, MessageSendParams.class);
            requestHandler.onMessageSendStream(sendParams, null).subscribe(new Flow.Subscriber<>() {
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
                        cancelUpstream.run();
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

    /**
     * params 解析失败按 JSON-RPC 规范归为 -32602（InvalidParams），而不是内部错误。
     *
     * <p><b>必须显式挡 {@code params: null}</b>（D16 七查补）：{@code treeToValue(NullNode, T)}
     * 不抛异常而是返回 {@code null}，一路传到 {@code requestHandler.onMessageSend(null, ...)}
     * 才炸成 NPE，被外层 {@code catch (Exception)} 归成 -32603「服务内部错误」——
     * 那会让调用方以为是我们坏了，实际是它自己没传参数。归错码比不报错更难查。
     */
    private static <T> T parse(JsonNode params, Class<T> type) {
        T value;
        try {
            value = Utils.OBJECT_MAPPER.treeToValue(params, type);
        } catch (Exception e) {
            throw new InvalidParamsError("参数格式错误: " + e.getMessage());
        }
        if (value == null) {
            throw new InvalidParamsError("params 不能为空");
        }
        return value;
    }

    private static ResponseEntity<String> json(String body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
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
