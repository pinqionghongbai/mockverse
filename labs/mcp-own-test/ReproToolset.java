package com.mockverse.mcp;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.mcp.McpToolset;
import com.google.adk.tools.mcp.StdioServerParameters;
import com.google.common.collect.ImmutableList;
import java.util.List;

/**
 * D4 issue 最小复现: 直接调用 ADK 公共 API McpToolset, 观察在 Windows 上 command="npx"(挂死?) vs "npx.cmd"(正常)
 * 的行为差异.
 */
public final class ReproToolset {

  public static void main(String[] args) {
    String cmd = args.length > 0 ? args[0] : "npx";
    System.out.println("[repro] command = " + cmd);
    StdioServerParameters params =
        StdioServerParameters.builder()
            .command(cmd)
            .args(
                ImmutableList.of(
                    "-y",
                    "@modelcontextprotocol/server-filesystem",
                    "E:/cluadeworkbase/mockverse-mcp-demo"))
            .build();

    long t0 = System.currentTimeMillis();
    try {
      McpToolset toolset = new McpToolset(params.toServerParameters());
      List<BaseTool> tools = toolset.getTools(null).toList().blockingGet();
      System.out.println(
          "[repro] OK in "
              + (System.currentTimeMillis() - t0)
              + "ms, tools loaded = "
              + tools.size());
    } catch (Throwable t) {
      System.out.println("[repro] ERROR in " + (System.currentTimeMillis() - t0) + "ms: " + t);
    }
    System.exit(0); // 确保退出(防调度线程滞留)
  }
}
