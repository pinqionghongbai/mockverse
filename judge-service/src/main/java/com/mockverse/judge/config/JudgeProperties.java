package com.mockverse.judge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 判题资源上限（{@code mockverse.judge.*}）。
 *
 * <p><b>每一个字段都是防"候选人代码把服务搞挂"的</b>：代码来自外部（LLM 转述的候选人作答），
 * 必须按不可信输入对待。这里给的是**子进程级**的约束——工作目录隔离、内存上限、超时、
 * 输出截断、并发上限。
 *
 * <p><b>它不是容器级沙盒</b>（D18 诚实边界）：跑的是宿主上的普通 JVM 子进程，代码仍能读写
 * 宿主文件系统、发起网络请求。演示/内网自用可以接受；对外提供服务必须换成容器或
 * seccomp/namespace 隔离——这一点写进 D18 日志，不许含糊。
 */
@Data
@ConfigurationProperties(prefix = "mockverse.judge")
public class JudgeProperties {

    /** MCP 端点路径。必须与 servlet 注册路径一致（SDK 用 {@code getRequestURI()} 比对）。 */
    private String endpoint = "/mcp";

    /** 每次判题的工作目录根。留空则用系统临时目录。 */
    private String workRoot = "";

    /** 编译超时。javac 冷启动 + 单文件通常 < 3s，20s 足够宽裕。 */
    private Duration compileTimeout = Duration.ofSeconds(20);

    /** 运行超时上限：请求里可以调小，但不能超过这个值。 */
    private Duration maxRunTimeout = Duration.ofSeconds(10);

    /** 请求未指定时的默认运行超时。 */
    private Duration defaultRunTimeout = Duration.ofSeconds(5);

    /** 子进程堆上限（MB）。 */
    private int maxHeapMb = 256;

    /** stdout / stderr 各自最多保留的字节数，超出部分读走丢弃（不截断会撑爆内存）。 */
    private int maxOutputBytes = 8000;

    /** 提交代码的最大长度（字符）。防止把整个仓库贴进来。 */
    private int maxCodeLength = 20000;

    /** 同时执行的判题数上限。超出的请求排队等待，避免一次并发把机器打满。 */
    private int maxConcurrent = 2;
}
