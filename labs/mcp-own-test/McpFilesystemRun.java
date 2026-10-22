package com.mockverse.mcp;

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

/** MockVerse D4 运行器: 验证 MCP 工具调用链路(DeepSeek -> McpToolset -> filesystem server). */
public final class McpFilesystemRun {

  public static void main(String[] args) throws Exception {
    String apiKey = System.getenv("DEEPSEEK_API_KEY");
    if (apiKey == null || apiKey.isBlank()) {
      System.err.println("缺少环境变量 DEEPSEEK_API_KEY, 无法启动.");
      System.exit(1);
    }

    String appName = "mockverse-mcp-app";
    String userId = "mockverse-mcp-user";
    String sessionId = UUID.randomUUID().toString();

    InMemorySessionService sessionService = new InMemorySessionService();
    Runner runner =
        new Runner(
            McpFilesystemAgent.ROOT_AGENT,
            appName,
            new InMemoryArtifactService(),
            sessionService,
            new InMemoryMemoryService());

    ConcurrentMap<String, Object> initialState = new ConcurrentHashMap<>();
    var unused =
        sessionService.createSession(appName, userId, initialState, sessionId).blockingGet();

    runOnce(runner, userId, sessionId, "列出当前目录下有哪些文件？");
    runOnce(runner, userId, sessionId, "读取 readme.txt 的内容并告诉我它写了什么。");
    runOnce(runner, userId, sessionId, "在目录里创建一个 notes.md 文件，内容为：MockVerse D4 MCP 链路验证成功。");

    // 不额外清理: MCP stdio server 随 JVM 退出而终止
    System.out.println("== D4 MCP 链路验证完成 ==");
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
