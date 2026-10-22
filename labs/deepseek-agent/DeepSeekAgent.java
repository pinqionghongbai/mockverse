package com.mockverse.agent;

import com.google.adk.agents.LlmAgent;
import com.google.adk.models.langchain4j.LangChain4j;
import com.google.adk.tools.FunctionTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

/**
 * MockVerse D3: 第一个接入 DeepSeek 的 ADK Agent.
 *
 * <p>模型链路: ADK LlmAgent -> contrib/langchain4j 桥接(适配器) -> LangChain4j OpenAiChatModel -> DeepSeek
 * [OI] 兼容端点. 工具(掷骰子/判素数)沿用官方 helloworld 示例, 便于对比验证.
 */
public final class DeepSeekAgent {

  /** DeepSeek 配置: 从环境变量读取, 严禁硬编码. */
  private static final String DEEPSEEK_BASE_URL = "https://api.deepseek.com";

  private static final String DEEPSEEK_MODEL = "deepseek-chat";

  public static final LlmAgent ROOT_AGENT =
      LlmAgent.builder()
          .name("mockverse_agent")
          .description("MockVerse 第一个 Agent, 运行在 DeepSeek 上, 能掷骰子和判断素数.")
          .model(
              LangChain4j.builder()
                  .chatModel(
                      OpenAiChatModel.builder()
                          .baseUrl(DEEPSEEK_BASE_URL)
                          .apiKey(System.getenv("DEEPSEEK_API_KEY"))
                          .modelName(DEEPSEEK_MODEL)
                          .build())
                  .modelName(DEEPSEEK_MODEL)
                  .build())
          .instruction(
              """
              你是一个掷骰子助手. 你可以掷出不同面数的骰子, 并回答与掷骰结果相关的问题.
              当被要求掷骰子时, 必须调用 roll_die 工具并传入面数(整数, 不知道面数时默认 6).
              当被要求判断素数时, 调用 check_prime 工具并传入整数列表. 不要自己心算素数.
              回答时必须包含掷骰结果和骰子面数.
              """)
          .tools(
              ImmutableList.of(
                  FunctionTool.create(DeepSeekAgent.class, "rollDie"),
                  FunctionTool.create(DeepSeekAgent.class, "checkPrime")))
          .build();

  private DeepSeekAgent() {}

  @SuppressWarnings("unchecked")
  public static ImmutableMap<String, Object> rollDie(int sides, ToolContext toolContext) {
    if (!toolContext.state().containsKey("rolls")) {
      Object unused = toolContext.state().put("rolls", new ArrayList<Integer>());
    }
    int result = new Random().nextInt(sides) + 1;
    ((ArrayList<Integer>) toolContext.state().get("rolls")).add(result);
    return ImmutableMap.of("result", result);
  }

  public static ImmutableMap<String, Object> checkPrime(List<Integer> nums) {
    HashSet<String> primes = new HashSet<>();
    for (int num : nums) {
      boolean isPrime = num >= 2;
      for (int i = 2; i <= Math.sqrt(num); i++) {
        if (num % i == 0) {
          isPrime = false;
          break;
        }
      }
      if (isPrime) {
        primes.add(String.valueOf(num));
      }
    }
    return ImmutableMap.of(
        "result",
        primes.isEmpty()
            ? "No prime numbers found."
            : String.join(", ", primes) + " are prime numbers.");
  }
}
