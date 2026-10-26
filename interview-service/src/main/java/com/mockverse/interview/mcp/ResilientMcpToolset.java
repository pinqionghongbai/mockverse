package com.mockverse.interview.mcp;

import com.google.adk.agents.ReadonlyContext;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import io.reactivex.rxjava3.core.Flowable;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 给远端 MCP 工具集加一层"失败即降级"的护栏（D18）。
 *
 * <p><b>为什么不能直接把 {@code McpToolset} 塞给 Agent</b>：ADK 每次 run 都会调
 * {@code getTools()}（{@code LlmAgent.canonicalTools}），而 {@code McpToolset} 内部
 * 在拿不到工具时是 {@code retryWhen} 重试 3 次后<b>抛 {@code McpToolLoadingException}</b>。
 * 这个异常会顺着 {@code Flowable.concat} 冒泡，整轮对话直接失败——判题服务没起，
 * 面试就答不了话，正好违反 D17 定下的"远端不可用时面试流程必须照常走"。
 *
 * <p><b>四层防护</b>：
 * <ol>
 *   <li>整体超时：{@code McpToolset} 的重试是 3 × 初始化超时，judge-service 挂死时最坏 30s。
 *       这里把整段加载夹到 {@code loadTimeout}，超时就当没有这个工具。</li>
 *   <li>错误吞掉：任何异常都转成空列表，本轮只是少一个工具。</li>
 *   <li>冷却：失败后 {@code cooldown} 内不再尝试，否则每一轮都要重新付一次超时代价。</li>
 *   <li>逐个工具再包一层 {@link DegradableTool}：工具"列出来之后、真正调用时"才失败的情况
 *       （判题服务中途挂掉）不会让整轮对话崩掉。</li>
 * </ol>
 *
 * <p><b>超时只是"有出口"，不是"能取消"</b>（D17 六查的教训）：{@code timeout} 会让调用方
 * 立刻拿到空列表，但被卡在 {@code client.initialize()} 里的那个 IO 线程仍然阻塞着，直到
 * 底层 HTTP 自己返回或断开。冷却期保证了这种泄漏最多每 {@code cooldown} 发生一次，
 * 而不是每轮一次。
 *
 * <p><b>首次加载必须串行</b>：{@code McpToolset} 里是
 * {@code if (mcpSession == null) mcpSession = createSession();}——check-then-act，没有同步。
 * 两场面试同时开跑就会各建一条 MCP 会话，多出来的那条再也没人 close。首次加载加锁串行，
 * 之后走无锁快路径（{@code warmed}）。注意不能在 {@code Flowable.defer} 的 lambda 里加
 * {@code synchronized}：lambda 只负责"造" Flowable，真正的加载发生在订阅之后、锁外面。
 */
@Slf4j
public class ResilientMcpToolset implements BaseToolset {

    private final String label;
    private final BaseToolset delegate;
    private final Duration loadTimeout;
    private final Duration cooldown;

    /** 冷却截止时刻（{@link System#nanoTime()} 口径）；0 表示当前可用。 */
    private volatile long blockedUntil;

    /** 是否已成功加载过一次；一旦为 true 就不再进锁。 */
    private volatile boolean warmed;

    /** 首轮加载的串行锁（见 {@link #loadCold}）。 */
    private final ReentrantLock warmupLock = new ReentrantLock();

    /**
     * 只用来跑**冷启动**的加载（见 {@link #loadTools}）。cached 而不是固定大小：
     * 加载是低频的，固定池会在并发首轮时排队，而排队时间会计入调用方的有界等待。
     */
    private static final ExecutorService LOADER = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mcp-tool-load");
        t.setDaemon(true);
        return t;
    });

    public ResilientMcpToolset(String label, BaseToolset delegate,
                               Duration loadTimeout, Duration cooldown) {
        this.label = label;
        this.delegate = delegate;
        this.loadTimeout = loadTimeout;
        this.cooldown = cooldown;
    }

    @Override
    public Flowable<BaseTool> getTools(ReadonlyContext readonlyContext) {
        long now = System.nanoTime();
        if (now < blockedUntil) {
            log.debug("{}：冷却中（剩余 {}ms），本轮不挂载该工具", label,
                    TimeUnit.NANOSECONDS.toMillis(blockedUntil - now));
            return Flowable.empty();
        }
        try {
            List<BaseTool> tools = loadTools(readonlyContext);
            blockedUntil = 0L;
            // 工具"列出来之后、真正调用时"也可能失败（判题服务中途挂掉），逐个包一层降级
            return Flowable.fromIterable(tools).map(DegradableTool::new);
        } catch (Exception e) {
            blockedUntil = System.nanoTime() + cooldown.toNanos();
            log.warn("{}：工具列表加载失败，{}s 内不再尝试，本轮按「没有该工具」继续。原因: {}",
                    label, cooldown.toSeconds(), innermost(e), e);
            return Flowable.empty();
        }
    }

    /**
     * 加载工具列表：**在调用线程上返回，但不让调用线程做慢 IO**。
     *
     * <p><b>为什么不切 Rx 线程</b>（D21 的修法）：D18 用
     * {@code .subscribeOn(Schedulers.io())} 把加载挪走，副作用是**整个 Agent 事件流从此跑在
     * io 线程上**。而 a2a SDK 0.3.2 的 {@code DefaultRequestHandler} 把
     * {@code AgentExecutor.execute()} 返回当作"Agent 执行完毕"并关闭事件队列——事件流一异步，
     * 队列就在 Agent 跑完之前被拆掉，任务永远停在 {@code working}（D19 发现、D20 定性）。
     * 试过在 A2A 侧包一层同步 Agent 去补救，**嵌套订阅同一个 Agent 流会直接挂死**（D21 实测），
     * 所以只能让工具流本身保持同步。
     *
     * <p><b>那 D18 担心的"阻塞事件循环"怎么办</b>：慢的只有冷启动的
     * {@code initialize()}（judge 挂死时最坏 3×init-timeout）。所以这里只把**冷启动**
     * 丢给 {@link #LOADER}，调用线程有界等待 {@code loadTimeout}；失败/超时就按"没有这个工具"
     * 继续，并进入冷却。已建会话后的 {@code listTools()} 是一次毫秒级往返，直接在调用线程跑。
     *
     * <p>净效果：**事件流始终在调用线程上**（A2A 契约成立），而最坏等待仍是
     * {@code loadTimeout}（与 D18 的 {@code .timeout()} 同一个量级）。
     */
    private List<BaseTool> loadTools(ReadonlyContext readonlyContext) throws Exception {
        if (warmed) {
            return delegate.getTools(readonlyContext).toList().blockingGet();
        }
        Future<List<BaseTool>> future = LOADER.submit(() -> loadCold(readonlyContext));
        try {
            return future.get(loadTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 中断等待。底层 HTTP 未必响应中断，靠冷却保证这种泄漏最多每 cooldown 发生一次
            future.cancel(true);
            throw new IllegalStateException(
                    label + "：工具加载超过 " + loadTimeout.toSeconds() + "s", e);
        }
    }

    /**
     * 冷启动：串行 + 一次性取完（在 {@link #LOADER} 线程上执行）。
     *
     * <p>这里用 {@code blockingGet} 是刻意的——只有把它读完，{@code mcpSession} 才真正建好，
     * 锁才有意义。此时已经在 worker 线程上，阻塞不影响调用方的事件流。
     *
     * <p><b>用 {@code tryLock} 而不是 {@code synchronized}</b>：如果 judge-service 挂死，
     * 首个加载线程会一直卡在 {@code initialize()} 里并**一直持有锁**。用 {@code synchronized}
     * 的话后面每一轮都会永久堵在监视器上（线程越积越多）；{@code tryLock} 最多等
     * {@code loadTimeout} 就放弃串行，退回"多建一条会话"的老路——宁可泄漏一条会话，
     * 也不能让整条链路跟着挂死。
     */
    private List<BaseTool> loadCold(ReadonlyContext readonlyContext) throws Exception {
        boolean locked = false;
        try {
            locked = warmupLock.tryLock(loadTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!locked) {
            log.warn("{}：等待首轮加载超过 {}s，本次不串行（可能已有一条会话卡在建连）",
                    label, loadTimeout.toSeconds());
            return delegate.getTools(readonlyContext).toList().blockingGet();
        }
        try {
            if (warmed) {
                return delegate.getTools(readonlyContext).toList().blockingGet();
            }
            List<BaseTool> tools = delegate.getTools(readonlyContext).toList().blockingGet();
            warmed = true;
            return tools;
        } finally {
            warmupLock.unlock();
        }
    }

    @Override
    public void close() throws Exception {
        delegate.close();
    }

    /** 取最内层原因：{@code McpToolLoadingException} 的 message 只说"重试后失败"，真正的根因在 cause 链尾部。 */
    private static String innermost(Throwable t) {
        Throwable cur = t;
        for (int i = 0; i < 8 && cur.getCause() != null && cur.getCause() != cur; i++) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }
}
