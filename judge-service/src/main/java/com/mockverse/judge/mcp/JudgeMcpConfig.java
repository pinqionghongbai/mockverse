package com.mockverse.judge.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mockverse.judge.config.JudgeProperties;
import com.mockverse.judge.judge.JavaJudge;
import com.mockverse.judge.judge.JudgeResult;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.http.HttpServlet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 {@code judge_code} 挂到一个真正的 MCP Server 上（D18）。
 *
 * <p><b>传输选 Streamable HTTP</b>：本服务是独立进程（8084），用 HTTP 暴露后客户端
 * （interview-service 的 {@code McpToolset}）只需知道一个 URL，不用管子进程生命周期；
 * 也能用 curl / MCP Inspector 单独验证，不必起整个面试链路。
 *
 * <p><b>servlet 注册路径必须等于 {@code mcpEndpoint}</b>：SDK 的传输实现拿
 * {@code request.getRequestURI()} 与 {@code mcpEndpoint} 比对（{@code javap -c} 实证），
 * 路径对不上会一律 404。
 *
 * <p><b>工具只回事实，不回评分</b>：能不能编译、跑出什么、有没有超时都是客观的；
 * "答得好不好"要靠面试官 Agent 拿着题干与参考答案判断。把判断权留在 Agent 侧，
 * 判题服务才能被任何场景复用。
 */
@Slf4j
@Configuration
public class JudgeMcpConfig {

    /** 工具名。与 interview-service 提示词、D18 文档里的名字必须一致。 */
    public static final String TOOL_NAME = "judge_code";

    @Bean
    public HttpServletStreamableServerTransportProvider mcpTransportProvider(
            JudgeProperties properties) {
        return HttpServletStreamableServerTransportProvider.builder()
                .mcpEndpoint(properties.getEndpoint())
                .build();
    }

    @Bean
    public ServletRegistrationBean<HttpServlet> mcpServlet(
            HttpServletStreamableServerTransportProvider provider, JudgeProperties properties) {
        ServletRegistrationBean<HttpServlet> registration =
                new ServletRegistrationBean<>(provider, properties.getEndpoint());
        registration.setName("mcpStreamableServlet");
        registration.setAsyncSupported(true);
        return registration;
    }

    /**
     * MCP Server 本体。
     *
     * <p>{@code destroyMethod = "closeGracefully"}：Spring 关容器时把 MCP 会话正常关掉，
     * 而不是让客户端在断开时收到半截响应。
     */
    @Bean(destroyMethod = "closeGracefully")
    public McpSyncServer mcpServer(HttpServletStreamableServerTransportProvider provider,
                                   JavaJudge judge, ObjectMapper objectMapper) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(TOOL_NAME)
                .title("Java 代码判题")
                .description("""
                        编译并运行候选人提交的 Java 代码，返回客观结果：能否编译、编译错误、
                        退出码、stdout/stderr、是否超时；给了 expectedOutput 时还会做输出比对。
                        本工具不评价代码好坏，只提供事实——判断正确性请结合题干与参考答案。
                        适合：候选人贴了代码、被要求写代码、或需要验证某段代码的实际行为时调用。""")
                .inputSchema(inputSchema())
                .build();

        McpSyncServer server = McpServer.sync(provider)
                .serverInfo("mockverse-judge", "0.1.0")
                .instructions("MockVerse 判题服务：提供 judge_code，真实编译/运行 Java 代码。")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(List.of(new McpServerFeatures.SyncToolSpecification(
                        tool, (exchange, request) -> call(request.arguments(), judge, objectMapper))))
                .build();

        log.info("MCP 判题服务就绪: 端点={}, 工具={}", "/mcp", TOOL_NAME);
        return server;
    }

    /**
     * 处理一次 {@code tools/call}。
     *
     * <p><b>{@code isError} 只表示"工具没能执行"</b>（参数非法、服务繁忙、环境异常）；
     * 编译失败与运行崩溃是**正常的判题结果**，走 {@code isError=false}，让模型拿到结构化事实
     * 去解释。混淆这两者会让模型把"候选人代码编译不过"误当成"判题工具坏了"。
     */
    private static McpSchema.CallToolResult call(Map<String, Object> args, JavaJudge judge,
                                                 ObjectMapper objectMapper) {
        String code = asString(args, "code");
        String stdin = asString(args, "stdin");
        String expectedOutput = asString(args, "expectedOutput");
        Integer timeoutSeconds = asInt(args, "timeoutSeconds");

        JudgeResult result = judge.judge(code, stdin, expectedOutput, timeoutSeconds);

        // 每次调用留一行事实日志：演示/排障时要能回答"这一轮到底有没有真的判过题"，
        // 只靠模型回复里的措辞是分不清"调了工具"和"模型自己猜的"
        log.info("judge_code: className={}, compiled={}, ran={}, timedOut={}, exit={}, "
                        + "matched={}, {}ms, error={}",
                result.className(), result.compiled(), result.ran(), result.timedOut(),
                result.exitCode(), result.outputMatched(), result.durationMs(), result.error());

        String json;
        try {
            json = objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            // 序列化失败只可能是我们自己的 record 有问题，兜底成可读文本
            json = "{\"error\":\"判题结果序列化失败: " + e.getOriginalMessage() + "\"}";
        }
        return McpSchema.CallToolResult.builder()
                .addTextContent(json)
                .isError(result.error() != null)
                .build();
    }

    private static Map<String, Object> inputSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("code", Map.of(
                "type", "string",
                "description", "候选人提交的完整 Java 源码。需要包含类定义（public 类会用作文件名），"
                        + "要运行的话还需有 main 方法。"));
        props.put("stdin", Map.of(
                "type", "string",
                "description", "可选：运行时喂给程序的 stdin 内容。"));
        props.put("expectedOutput", Map.of(
                "type", "string",
                "description", "可选：期望的 stdout。给出时工具会比对并返回 outputMatched；"
                        + "比对会忽略行尾空白与首尾空行。"));
        props.put("timeoutSeconds", Map.of(
                "type", "integer",
                "description", "可选：运行超时秒数，默认 5，超过服务端上限会被夹到上限。"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("code"));
        return schema;
    }

    private static String asString(Map<String, Object> args, String key) {
        if (args == null) {
            return null;
        }
        Object v = args.get(key);
        return v == null ? null : v.toString();
    }

    private static Integer asInt(Map<String, Object> args, String key) {
        if (args == null) {
            return null;
        }
        Object v = args.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                // 非法值按"未指定"处理，由服务端用默认超时兜底
            }
        }
        return null;
    }
}
