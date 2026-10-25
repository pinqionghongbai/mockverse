package com.mockverse.interview.a2a;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 给 a2a SDK 的 HTTP 调用套一层超时（D17 六查）。
 *
 * <p><b>为什么必须自己套</b>：SDK 的 {@code JdkA2AHttpClient} 建 {@code HttpRequest} 时只设了
 * URI，既没有 {@code connectTimeout} 也没有 {@code HttpRequest.timeout()}。对端"连得上但一个
 * 字节都不回"时（LLM 接口黑洞、进程假死、防火墙静默丢包），{@code send()} 会**永久**阻塞。
 * 上层 {@code AgentRuntime} 的整轮超时只能让调用方拿到降级话术，卡在 {@code send()} 里的
 * 线程不会因此解开——每挂一次就永久少一个线程。
 *
 * <p><b>不改变阻塞语义</b>：调用方线程在 {@link Future#get} 上同步阻塞到调用结束或超时，
 * 只是把"可被打断的那一段"挪到独立线程——{@code HttpClient.send} 声明了
 * {@code InterruptedException}，{@code cancel(true)} 能让它立刻退出。
 * 这一点对 {@link A2ARemoteAgent} 是硬约束：ADK 的 {@code AgentExecutor.execute} 靠
 * "订阅同步阻塞"来界定一次 run 的生命周期（D17 坑 1），所以**不能**换成 {@code subscribeOn}。
 *
 * <p><b>异常口径</b>：超时/中断抛 {@link IllegalStateException}，消息里带主语和秒数，
 * 交给 {@code AgentRuntime} 的三层兜底统一降级；任务内部抛出的 {@link RuntimeException}
 * 原样重抛（远端不可达是 {@code A2AClientException}，调用方按它归因）。
 */
@Slf4j
public final class A2AHttpCalls {

    /**
     * 跑 HTTP 调用的线程池（守护线程）。共享一个无界池：每次调用只占一个线程，
     * 调用结束即归还；挂死的调用最多占用到超时为止。
     */
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "a2a-http-call");
        t.setDaemon(true);
        return t;
    });

    private A2AHttpCalls() {
    }

    /**
     * @param label   失败消息的主语，例如 {@code "调用 mockverse-knowledge"}
     * @param timeout 上限；null 表示不设上限
     * @param task    实际的阻塞调用
     */
    public static <T> T call(String label, Duration timeout, Callable<T> task) {
        Future<T> future = EXECUTOR.submit(task);
        try {
            return timeout == null
                    ? future.get()
                    : future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            // 不把 TimeoutException 挂成 cause：AgentRuntime.rootMessage 会一路剥到最内层，
            // 日志里就只剩 "TimeoutException: null"——哪个远端、超了多少秒全丢了
            log.warn("{}：超时（{}s），已中断", label, timeout.toSeconds());
            throw new IllegalStateException(label + "：超时（" + timeout.toSeconds() + "s），已中断");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            log.warn("{}：被中断", label, e);
            throw new IllegalStateException(label + "：被中断");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(label + "：失败: " + cause, cause);
        }
    }
}
