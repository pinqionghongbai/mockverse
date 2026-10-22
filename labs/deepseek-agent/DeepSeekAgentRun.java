package com.mockverse.agent;

import com.google.adk.agents.RunConfig;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** MockVerse D3 运行器: 验证 DeepSeek 驱动的 Agent 能完成"掷骰子+判素数"多轮工具调用. */
public final class DeepSeekAgentRun {

  public static void main(String[] args) {
    String apiKey = System.getenv("DEEPSEEK_API_KEY");
    if (apiKey == null || apiKey.isBlank()) {
      System.err.println("缺少环境变量 DEEPSEEK_API_KEY, 无法启动.");
      System.exit(1);
    }

    String appName = "mockverse-app";
    String userId = "mockverse-user";
    String sessionId = UUID.randomUUID().toString();

    InMemorySessionService sessionService = new InMemorySessionService();
    Runner runner =
        new Runner(
            DeepSeekAgent.ROOT_AGENT,
            appName,
            new InMemoryArtifactService(),
            sessionService,
            new InMemoryMemoryService());

    ConcurrentMap<String, Object> initialState = new ConcurrentHashMap<>();
    var unused =
        sessionService.createSession(appName, userId, initialState, sessionId).blockingGet();

    runOnce(runner, userId, sessionId, "你好！请掷一个 60 面的骰子。");
    runOnce(runner, userId, sessionId, "把刚才的结果和 17、25 一起判断哪些是素数。");
    runOnce(runner, userId, sessionId, "最后用一句话告诉我你是哪个模型驱动的。");
  }

  private static void runOnce(Runner runner, String userId, String sessionId, String prompt) {
    System.out.println("You> " + prompt);
    Content userMessage =
        Content.builder()
            .role("user")
            .parts(ImmutableList.of(Part.builder().text(prompt).build()))
            .build();
    Flowable<Event> eventStream =
        runner.runAsync(userId, sessionId, userMessage, RunConfig.builder().build());
    List<Event> events = Lists.newArrayList(eventStream.blockingIterable());

    StringBuilder sb = new StringBuilder("Agent> ");
    for (Event event : events) {
      sb.append(event.stringifyContent().stripTrailing());
    }
    System.out.println(sb);
    System.out.println();
  }
}
