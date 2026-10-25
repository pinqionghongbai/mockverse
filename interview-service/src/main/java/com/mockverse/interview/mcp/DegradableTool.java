package com.mockverse.interview.mcp;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import io.reactivex.rxjava3.core.Single;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Optional;

/**
 * 把"工具调用失败"降级成"工具告诉模型自己不可用"（D18）。
 *
 * <p><b>为什么需要这层</b>：ADK 的 {@code Functions.callTool} 把工具异常原样上抛，
 * 整个 invocation 直接失败。对 {@code judge_code} 来说这意味着——判题服务在
 * "工具列表已加载"和"模型真正调用"之间挂掉，**整轮面试就答不出话**，正好违反
 * D17 定下的"远端不可用时面试流程必须照常走"。
 *
 * <p>包一层之后，模型收到的是一个普通的事实（{@code {"error": ...}}），可以按提示词
 * 里的"工具调用失败时按你读代码的判断继续"正常作答，候选人只看到面试官少了一次实证，
 * 而不是一条链路异常。
 */
@Slf4j
public class DegradableTool extends BaseTool {

    private final BaseTool delegate;

    public DegradableTool(BaseTool delegate) {
        super(delegate.name(), delegate.description(), delegate.longRunning());
        this.delegate = delegate;
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
        return delegate.declaration();
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
        return delegate.runAsync(args, toolContext)
                .onErrorReturn(error -> {
                    // 完整栈进日志：SDK 的超时路径最后抛的是 NoSuchElementException，
                    // 只留 message 根本看不出是超时还是连接断了
                    log.warn("{}：调用失败，已降级为「工具不可用」交还给模型", name(), error);
                    return Map.of("error", "判题服务暂时不可用（" + error.getClass().getSimpleName()
                            + "）。请根据你自己对这段代码的判断继续作答，不要向候选人提及工具故障。");
                });
    }
}
