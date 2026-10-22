package com.mockverse.mcp;

import com.google.adk.agents.LlmAgent;
import com.google.adk.models.langchain4j.LangChain4j;
import com.google.adk.tools.mcp.McpToolset;
import com.google.adk.tools.mcp.StdioServerParameters;
import com.google.common.collect.ImmutableList;
import dev.langchain4j.model.openai.OpenAiChatModel;

/**
 * MockVerse D4: 接入 MCP filesystem 服务器的 DeepSeek Agent.
 *
 * <p>链路: ADK LlmAgent -> McpToolset(STDIO 传输) -> npx 拉起的 filesystem server. 与 D3 的差异: 工具不再进程内
 * FunctionTool, 而是经 MCP 协议跨进程调用.
 */
public final class McpFilesystemAgent {

  private static final String DEEPSEEK_BASE_URL = "https://api.deepseek.com";
  private static final String DEEPSEEK_MODEL = "deepseek-chat";

  public static final LlmAgent ROOT_AGENT =
      LlmAgent.builder()
          .name("mockverse_mcp_agent")
          .description("MockVerse D4: 通过 MCP filesystem 服务器做文件操作的 Agent.")
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
              你是一个文件系统助手. 使用提供的 MCP 工具来读取/写入/搜索文件与目录.
              当被要求列出文件时, 调用目录列表工具. 当被要求读取文件时, 调用读取工具.
              回答时说明你调用了哪个工具.
              """)
          .tools(ImmutableList.of(createMcpToolset()))
          .build();

  private McpFilesystemAgent() {}

  private static McpToolset createMcpToolset() {
    StdioServerParameters stdioParams =
        StdioServerParameters.builder()
            // Windows 上 npx 是批处理脚本(npx.cmd), Java ProcessBuilder 直接 spawn "npx" 会挂死,
            // 必须显式用 npx.cmd
            .command("npx.cmd")
            .args(
                ImmutableList.of(
                    "-y",
                    "@modelcontextprotocol/server-filesystem",
                    "E:\\cluadeworkbase\\mockverse-mcp-demo"))
            .build();
    return new McpToolset(stdioParams.toServerParameters());
  }
}
