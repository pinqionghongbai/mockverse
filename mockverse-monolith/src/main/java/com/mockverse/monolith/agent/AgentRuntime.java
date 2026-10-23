package com.mockverse.monolith.agent;

import com.google.adk.agents.RunConfig;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.common.collect.Lists;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.mockverse.monolith.config.InterviewProperties;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ADK 运行时: 统一持有会话/产物/记忆三类服务, 并负责把事件流收敛成一段文本。
 *
 * <p><b>当前局限（D13 要还的债）</b>: 三类服务都是 InMemory 实现, 进程重启后会话丢失。
 * D13 的"聊天记录入库 + 会话恢复"会把 SessionService 换成 DB 实现, 或改为每轮用
 * chat_message 表重建上下文。
 */
@Component
public class AgentRuntime {

    private final InterviewProperties properties;

    @Getter
    private final InMemorySessionService sessionService = new InMemorySessionService();

    private final InMemoryArtifactService artifactService = new InMemoryArtifactService();
    private final InMemoryMemoryService memoryService = new InMemoryMemoryService();

    public AgentRuntime(InterviewProperties properties) {
        this.properties = properties;
    }

    /** 为某个 Agent 建 Runner（轻量对象，可重复创建）。 */
    public Runner runnerFor(com.google.adk.agents.BaseAgent agent) {
        return new Runner(agent, properties.getAppName(), artifactService, sessionService, memoryService);
    }

    /** 创建 ADK 会话；已存在则复用。 */
    public void ensureSession(String userId, String adkSessionId) {
        var existing = sessionService
                .getSession(properties.getAppName(), userId, adkSessionId, Optional.empty())
                .blockingGet();
        if (existing == null) {
            sessionService
                    .createSession(properties.getAppName(), userId, new ConcurrentHashMap<String, Object>(), adkSessionId)
                    .blockingGet();
        }
    }

    /**
     * 跑一轮对话并返回纯文本回复。
     *
     * <p>只收集 text 类型的 part —— 事件流里还有 functionCall / functionResponse,
     * 直接 {@code stringifyContent()} 会把工具报文也拼进回答里。
     */
    public String chat(Runner runner, String userId, String adkSessionId, String userText) {
        Content message = Content.builder()
                .role("user")
                .parts(List.of(Part.fromText(userText)))
                .build();

        List<Event> events = Lists.newArrayList(
                runner.runAsync(userId, adkSessionId, message, RunConfig.builder().build())
                        .blockingIterable());

        StringBuilder sb = new StringBuilder();
        for (Event e : events) {
            e.content().ifPresent(content ->
                    content.parts().ifPresent(parts ->
                            parts.forEach(p -> p.text().ifPresent(sb::append))));
        }
        return sb.toString().trim();
    }
}
