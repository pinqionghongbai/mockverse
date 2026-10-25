package com.mockverse.interview.a2a;

import com.google.adk.a2a.converters.EventConverter;
import com.google.adk.a2a.converters.ResponseConverter;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.utils.AgentEnums.AgentOrigin;
import com.google.genai.types.Content;
import io.a2a.client.transport.jsonrpc.JSONRPCTransport;
import io.a2a.spec.AgentCard;
import io.a2a.spec.EventKind;
import io.a2a.spec.Message;
import io.a2a.spec.MessageSendConfiguration;
import io.a2a.spec.MessageSendParams;
import io.a2a.spec.Task;
import io.a2a.spec.TaskState;
import io.reactivex.rxjava3.core.Flowable;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 跨进程子 Agent：把 ADK 的一轮调用翻译成 A2A {@code message/send}，再把响应翻译回 ADK {@link Event}。
 *
 * <p><b>为什么不直接用 ADK 自带的 {@code com.google.adk.a2a.agent.RemoteA2AAgent}</b>
 * （D17 实测得出，两条都是硬需求）：
 * <ol>
 *   <li><b>它没有传 metadata 的路径</b>：{@code prepareMessage()} 自己拼 {@link Message} 且全类
 *       不设 metadata，调的是不带 metadata 的 {@code sendMessage} 重载；而 resume-service 的
 *       {@code AgentExecutor} 只认 {@code params.metadata}。没有 metadata，简历诊断会归档到
 *       默认用户 0——不报错，只是记录静默落到别人名下（D16 二十一查）。</li>
 *   <li><b>它不区分 part 类型</b>：{@code ResponseConverter.taskToEvent} 会把 artifact 里
 *       所有 part 都塞进 {@code Event.content}，其中 {@code functionCall} /
 *       {@code functionResponse} 的 DataPart 会被还原成 genai 的 {@code functionCall} part
 *       （{@code PartConverter.convertDataPartToGenAiPart}）。父模型随后会看到一条"model 角色
 *       自带 functionCall"的消息，既没有配对的 functionResponse、也不是它自己发起的——
 *       轻则污染上下文，重则让下一轮 LLM 请求报 400。</li>
 * </ol>
 *
 * <p><b>协议实现仍是官方的</b>：报文构造/解析走 {@link JSONRPCTransport}（a2a SDK），
 * ADK 事件互转走 {@link EventConverter} / {@link ResponseConverter}（ADK 的 a2a 模块）。
 * 本类只补上"消息怎么拼、响应怎么筛"这两件官方类没做的事，不重写协议。
 *
 * <p><b>只走阻塞式 {@code message/send}</b>：三张卡都声明支持流式，但跨进程子 Agent 的
 * 输出最终要拼成一条给父模型的文本，流式的分块聚合对 demo 没有收益（D16 建议 7）。
 * 所以这里刻意不实现 {@code message/stream}，也不要在文档里写"跨进程流式"。
 * <b>副作用（D17 实测）</b>：父服务的 SSE 端点里，跨进程子 Agent 只产出**一个** chunk
 * （整块吐出），只有进程内的 interviewer 才是逐字流。
 *
 * <p><b>失败一律抛异常</b>：远端不可达、返回 FAILED、返回非 COMPLETED 状态、返回未知类型
 * 都抛出去，交给 {@code AgentRuntime.converse} 的三层兜底统一降级——这里不做静默吞掉，
 * 否则面试官会对着一句空话继续追问。
 *
 * <p><b>调用有上限</b>（D17 六查）：SDK 的 HTTP 客户端没有读超时，远端挂死会永久占住订阅
 * 线程。这里给每次调用加 {@code remoteTimeout}，到点中断 HTTP 请求并抛异常走统一降级。
 */
@Slf4j
public class A2ARemoteAgent extends BaseAgent {

    private final AgentCard agentCard;
    private final JSONRPCTransport transport;

    /**
     * 单次远端调用的上限。
     *
     * <p>值为 null 表示不设上限（仅测试用，生产配置恒非 null）。为什么需要上限、为什么不
     * 违反坑 1 的阻塞约束，见 {@link A2AHttpCalls}。
     */
    private final Duration remoteTimeout;

    /**
     * 是否把 {@code session.userId()} 塞进 {@code params.metadata.userId}。
     *
     * <p>只有 resume 需要：简历诊断要归档到真实用户名下。knowledge 不需要用户身份，
     * 传过去反而多一份"谁能读谁的数据"的模糊地带（A2A 端点目前没有鉴权，D25 才补）。
     */
    private final boolean forwardSessionUserId;

    /**
     * @param name        子 Agent 名，必须与 coordinator 提示词里的 {@code transfer_to_agent} 目标一致
     * @param description 注入给 coordinator 做路由，写成"什么样的消息该给我"（D12 的经验）
     * @param agentCard   远端卡片；已由调用方解析好，本类不再联网
     * @param remoteTimeout 单次调用上限；null 表示不设上限
     */
    public A2ARemoteAgent(String name, String description, AgentCard agentCard,
                          boolean forwardSessionUserId, Duration remoteTimeout) {
        super(name, description, List.of(), List.of(), List.of());
        this.agentCard = agentCard;
        this.transport = new JSONRPCTransport(agentCard);
        this.forwardSessionUserId = forwardSessionUserId;
        this.remoteTimeout = remoteTimeout;
    }

    @Override
    protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
        if (invocationContext.session().events().isEmpty()) {
            // 与 ADK 的 RemoteA2AAgent 同口径：没有历史就没有可发的消息
            log.warn("会话为空，跳过对远端 {} 的调用", agentCard.name());
            return Flowable.empty();
        }

        Message message = buildMessage(invocationContext);
        if (message.getParts() == null || message.getParts().isEmpty()) {
            // 空 parts 会被服务端归成 -32602，但那看起来像"调用方传错了参数"，
            // 实际是父侧没产出任何可转发的文本。在本地就挡掉，错误归因才准确。
            log.warn("本轮没有可转发的文本 part，跳过对远端 {} 的调用", agentCard.name());
            return Flowable.empty();
        }

        MessageSendParams params = buildParams(message, invocationContext);
        // 跨进程这一跳没有 traceId，起止两行 debug 是唯一能判断"卡在父侧还是远端"的线索
        log.debug("调用远端 {} 开始", agentCard.name());
        // 绝对不能加 subscribeOn：ADK 的 AgentExecutor.execute 靠"订阅同步阻塞"来界定一次 run 的
        // 生命周期——它 subscribe 后立刻返回，而 A2A 侧的 EnhancedRunnable 把 execute() 的返回当作
        // "Agent 执行完毕"，随即 DefaultRequestHandler 关闭事件队列并读 TaskStore 快照返回。
        // 一旦订阅被挪到别的调度器，HTTP 会带着 working 快照提前返回，远端稍后产出的文本
        // 全部落进已关闭的队列（D17 实测：0.55s 返回 working，3.2s 后远端才答完）。
        // 官方 RemoteA2AAgent 同样用 Flowable.create + dispatchSynchronously，理由一致。
        return Flowable.fromCallable(() -> callRemote(params))
                .map(kind -> toEvent(kind, invocationContext))
                .doOnNext(e -> log.debug("远端 {} 产出事件 author={} turnComplete={}",
                        agentCard.name(), e.author(), e.turnComplete()));
    }

    /**
     * 带超时地调远端 {@code message/send}（超时的理由与阻塞语义见 {@link A2AHttpCalls}）。
     *
     * <p>订阅线程依然同步阻塞到远端答完，{@code AgentExecutor.execute()} 照旧在远端返回之后
     * 才结束——这里只是让"远端挂死"有一个到点中断的出口。
     */
    private EventKind callRemote(MessageSendParams params) {
        return A2AHttpCalls.call("调用 " + agentCard.name(), remoteTimeout,
                () -> transport.sendMessage(params, null));
    }

    /**
     * 拼发给远端的用户消息。
     *
     * <p>parts 取自 {@link EventConverter#messagePartsFromContext}——它只收集"本 Agent 上次
     * 发言之后"的事件，正是这一轮该转发的增量。不用 {@code findUserFunctionCall} 那条分支：
     * 那是 A2A 侧的任务续跑场景，本服务不涉及。
     */
    private Message buildMessage(InvocationContext invocationContext) {
        return new Message.Builder()
                .messageId(invocationContext.newUuid())
                .role(Message.Role.USER)
                .parts(new ArrayList<>(EventConverter.messagePartsFromContext(invocationContext)))
                .build();
    }

    private MessageSendParams buildParams(Message message, InvocationContext invocationContext) {
        MessageSendParams.Builder builder = new MessageSendParams.Builder()
                .message(message)
                .configuration(new MessageSendConfiguration.Builder()
                        .acceptedOutputModes(List.of("text"))
                        // 阻塞式：一次调用等一个完整 Task，而不是订阅 SSE
                        .blocking(true)
                        .build());

        if (forwardSessionUserId) {
            String userId = invocationContext.session().userId();
            if (isNumericUserId(userId)) {
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("userId", userId);
                builder.metadata(metadata);
            } else if (userId != null && !userId.isBlank()) {
                // 从 A2A 端点进来的请求，session userId 是 ADK 的 AgentExecutor 强写的
                // A2A_USER_<contextId>——那是会话标识，不是业务用户身份。原样转发会让下游
                // resume-service 打「metadata.userId 不是合法数字」，把「调用方没提供用户身份」
                // 误报成「调用方传错了值」，排查时会去查一个根本不存在的问题（D17 六查实测）。
                log.debug("会话 userId={} 不是业务用户 ID（A2A 入口的 ADK 会话标识），不转发", userId);
            }
        }
        return builder.build();
    }

    /**
     * 只有能解析成 {@code Long} 的才算业务用户 ID。
     *
     * <p>判据与下游严格对齐：resume-service 的 {@code ResumeTools.resolveUserId} 就是用
     * {@code Long.valueOf} 解析 {@code metadata.userId} 的，传一个它解析不了的值只会让它的
     * 归因从「调用方没提供」变成「调用方传了非法值」。这里不认 ADK 的 {@code A2A_USER_} 前缀，
     * 因为那是 ADK 的实现细节；认"能不能解析成 Long"才是与业务契约一致的标准。
     */
    private static boolean isNumericUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        try {
            Long.parseLong(userId);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * A2A 响应 → ADK 事件。
     *
     * <p>{@code message/send} 的返回值只有两种：{@link Task}（走了任务状态机）或
     * {@link Message}（直接回话）。两种都交给官方转换器，本类只做一件事——
     * 把非文本 part 筛掉（原因见类注释第 2 条）。
     */
    private Event toEvent(EventKind kind, InvocationContext invocationContext) {
        if (kind instanceof Task task) {
            TaskState state = task.getStatus().state();
            if (state == TaskState.FAILED) {
                // 远端自己认输时，status.message 里通常写着原因；拿不到就给状态名
                String reason = Optional.ofNullable(task.getStatus().message())
                        .map(m -> textOf(m.getParts()))
                        .filter(s -> !s.isBlank())
                        .orElse("远端返回 FAILED 但未给出原因");
                throw new IllegalStateException(agentCard.name() + " 处理失败: " + reason);
            }
            if (state != TaskState.COMPLETED) {
                // blocking=true 的契约就是"等到终态再回"。拿到非 COMPLETED 说明对端提前放了快照
                // （D17 实测过：客户端异步订阅时 0.55s 就回 working）。这种快照里通常只有
                // transfer_to_agent 的 functionCall，当成答案会让面试官对着空话继续追问——
                // 宁可抛出去走统一降级，也不静默收下半个结果。
                throw new IllegalStateException(
                        agentCard.name() + " 未执行完成，返回状态 " + state);
            }
            return textOnly(ResponseConverter.taskToEvent(task, invocationContext));
        }
        if (kind instanceof Message message) {
            return textOnly(ResponseConverter.messageToEvent(message, invocationContext));
        }
        throw new IllegalStateException(
                "未知的 A2A 响应类型: " + (kind == null ? "null" : kind.getClass().getName()));
    }

    /**
     * 只保留文本 part（并丢掉 thought）。
     *
     * <p>不这么做的后果见类注释：父模型会收到一条自带 {@code functionCall} 的 model 消息。
     * 筛选后如果一条文本都不剩，仍然返回事件（内容为空）——{@code AgentRuntime} 会把它当
     * "本轮没有有效输出"处理，比在这里吞掉更能暴露问题。
     */
    private static Event textOnly(Event event) {
        Optional<Content> content = event.content();
        if (content.isEmpty() || content.get().parts().isEmpty()) {
            return event;
        }
        List<com.google.genai.types.Part> all = content.get().parts().get();
        List<com.google.genai.types.Part> texts = all.stream()
                .filter(p -> p.text().isPresent() && !p.thought().orElse(false))
                .toList();
        if (texts.size() == all.size()) {
            return event;
        }
        log.debug("远端 {} 的响应含 {} 个非文本 part，已筛掉", event.author(), all.size() - texts.size());
        event.setContent(Content.builder()
                .role(content.get().role().orElse("model"))
                .parts(texts)
                .build());
        return event;
    }

    private static String textOf(List<io.a2a.spec.Part<?>> parts) {
        if (parts == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (io.a2a.spec.Part<?> part : parts) {
            if (part instanceof io.a2a.spec.TextPart textPart && textPart.getText() != null) {
                sb.append(textPart.getText());
            }
        }
        return sb.toString().trim();
    }

    @Override
    protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
        throw new UnsupportedOperationException("A2ARemoteAgent 不支持 runLive（双向实时流）");
    }

    @Override
    public AgentOrigin toolOrigin() {
        return AgentOrigin.A2A;
    }
}
