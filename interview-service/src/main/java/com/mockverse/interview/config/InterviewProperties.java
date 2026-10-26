package com.mockverse.interview.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 面试模块配置。提示词版本可在 application.yml 里切换, 便于 A-B 对照。 */
@Data
@Component
@ConfigurationProperties(prefix = "mockverse.interview")
public class InterviewProperties {

    /** ADK 应用名, 用于隔离会话命名空间 */
    private String appName = "mockverse";

    /**
     * 每场面试最大轮数。
     *
     * <p><b>为什么是 volatile</b>（D23）：这个值会被 Nacos 的配置监听线程改写
     * （{@code NacosConfigRefresher.apply}），而读取它的是 Tomcat 的请求线程
     * （{@code InterviewService.start}）。没有 volatile 时，写线程的更新对读线程
     * **不保证可见**——表现为"Nacos 控制台改了，日志也打了生效，但新会话还是旧轮数"，
     * 而且这种问题在开发机上看不出来（x86 + 短时间窗内缓存会自己刷）。
     * 一个 int 的 volatile 读写代价可以忽略，换的是确定的语义。
     */
    private volatile int maxRounds = 5;

    /** 提示词版本: v1 / v2 / v3 (D9 下午 A-B 对照用) */
    private String promptVersion = "v3";
}
