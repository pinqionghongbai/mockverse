package com.mockverse.monolith.config;

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

    /** 每场面试最大轮数 */
    private int maxRounds = 5;

    /** 提示词版本: v1 / v2 / v3 (D9 下午 A-B 对照用) */
    private String promptVersion = "v3";
}
