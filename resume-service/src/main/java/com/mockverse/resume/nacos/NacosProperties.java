package com.mockverse.resume.nacos;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Nacos 接入参数（D23）。
 *
 * <p><b>为什么直接用 nacos-client 而不是 spring-cloud-starter-alibaba-nacos-discovery</b>：
 * 本项目是四个独立 Spring Boot 服务，没有 Spring Cloud 生态（无 OpenFeign / Gateway /
 * LoadBalancer），引入 SCA 会把整套 Spring Cloud 上下文带进来——只为拿"注册 + 配置监听"
 * 这两件事，代价是启动时间、依赖面、以及 SCA 2023.0.x 与 Boot 3.3.x 的版本线对齐问题。
 * nacos-client 是官方 SDK，SCA 的 starter 本身也只是它的封装，直接用不损失任何能力。
 *
 * <p><b>代价（诚实标注）</b>：SCA 的 {@code spring.cloud.nacos.discovery} 有大量开箱配置
 * （元数据、集群、权重、健康检查策略），这里只实现了演示所需的最小子集：
 * 临时实例注册 + 自动心跳 + 优雅注销 + 配置监听。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mockverse.nacos")
public class NacosProperties {

    /** 关掉后服务不注册、不监听配置——单元测试和"只想跑本地链路"时用。 */
    private boolean enabled = true;

    /** Nacos 服务端地址。容器名 mockverse-nacos，宿主 8848。 */
    private String serverAddr = "localhost:8848";

    /** 命名空间。空串 = public（与容器默认一致，不用额外建命名空间）。 */
    private String namespace = "";

    /** 分组。 */
    private String group = "DEFAULT_GROUP";

    /** 集群名。单机部署给 DEFAULT。 */
    private String clusterName = "DEFAULT";

    /**
     * 是否注册为临时实例（ephemeral）。
     *
     * <p>必须是 true 才能拿到自动心跳：临时实例由客户端每 5s 续约，进程挂掉后
     * Nacos 在 15s 未收到心跳即标记不健康、30s 后剔除。非临时实例要服务端主动探活，
     * 单机版配置更麻烦且演示无收益。
     */
    private boolean ephemeral = true;

    /** 配置监听的 data-id。默认按服务名取，与 {@code spring.application.name} 同值。 */
    private String configDataId = "";

    /**
     * 注册用的 IP。
     *
     * <p>留空时用 {@code InetAddress.getLocalHost()}——本机演示够用。
     * 但多网卡机器（Hyper-V / Docker / VPN 虚拟网卡）上它可能返回一个别的机器
     * 连不通的地址，这时要显式指定。
     */
    private String ip = "";
}
