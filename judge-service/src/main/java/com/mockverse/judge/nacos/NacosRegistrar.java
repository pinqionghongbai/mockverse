package com.mockverse.judge.nacos;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.Properties;

/**
 * 把当前服务注册到 Nacos（D23）。
 *
 * <p><b>注册时机选 {@code ApplicationReadyEvent} 而不是 {@code @PostConstruct}</b>：
 * 只有端口真正绑定、Agent 编排树建完、种子数据灌完之后，"这个服务可用"才是事实。
 * 早注册会让 Nacos 控制台先显示服务、消费者随即调用却拿到 503——注册表撒谎比没有注册表更糟。
 *
 * <p><b>注册失败不阻断启动</b>：Nacos 挂了服务本身仍能对外提供能力（HTTP/A2A 端点都在），
 * 只是控制台看不到。这与 {@code QuestionSeeder} 的降级启动同一口径——
 * 注册中心是"发现手段"，不是"服务本体"。
 *
 * <p><b>优雅注销</b>：{@code ContextClosedEvent} 时主动 deregister，不等 Nacos 心跳超时
 * （默认 15s 不健康 + 30s 剔除）。演示时"杀进程"会走不到这个钩子，但 Ctrl-C / 正常关闭会。
 */
@Slf4j
@Component
public class NacosRegistrar {

    private final NacosProperties props;
    private final String appName;
    private final int port;

    private NamingService namingService;
    private Instance instance;

    public NacosRegistrar(NacosProperties props,
                          @Value("${spring.application.name}") String appName,
                          @Value("${server.port}") int port) {
        this.props = props;
        this.appName = appName;
        this.port = port;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        if (!props.isEnabled()) {
            log.info("Nacos 注册已关闭（mockverse.nacos.enabled=false），本服务不注册");
            return;
        }
        try {
            Properties cfg = new Properties();
            cfg.put("serverAddr", props.getServerAddr());
            if (!props.getNamespace().isBlank()) {
                cfg.put("namespace", props.getNamespace());
            }
            namingService = NacosFactory.createNamingService(cfg);

            instance = new Instance();
            instance.setIp(resolveIp());
            instance.setPort(port);
            instance.setServiceName(appName);
            instance.setClusterName(props.getClusterName());
            instance.setEphemeral(props.isEphemeral());
            // 元数据给消费方一点上下文：A2A 服务发现路径是固定的，但 HTTP 前缀会变
            instance.addMetadata("framework", "spring-boot-3.3.1");
            instance.addMetadata("agent-card", "/.well-known/agent-card.json");

            namingService.registerInstance(appName, props.getGroup(), instance);
            log.info("已注册到 Nacos: {}:{} (server={}, group={}, ephemeral={})",
                    instance.getIp(), port, props.getServerAddr(), props.getGroup(), props.isEphemeral());
        } catch (Exception e) {
            // 注册中心不可用不该拖垮服务本体（与题库种子的降级启动同一口径）
            log.warn("注册到 Nacos 失败，服务继续运行（控制台看不到本实例）: {}: {}",
                    e.getClass().getSimpleName(), e.getMessage());
        }
    }

    @EventListener(ContextClosedEvent.class)
    public void deregister() {
        if (namingService == null || instance == null) {
            return;
        }
        try {
            namingService.deregisterInstance(appName, props.getGroup(), instance);
            log.info("已从 Nacos 注销: {}:{}", instance.getIp(), port);
        } catch (NacosException e) {
            log.warn("从 Nacos 注销失败（临时实例会在心跳超时后自动剔除）: {}", e.getMessage());
        }
    }

    /**
     * 解析注册 IP；显式配置优先，否则取本机地址。
     *
     * <p><b>为什么要过滤 198.18.0.0/15</b>（D23 实测踩到）：本机开了代理的 fake-ip 模式后，
     * {@code InetAddress.getLocalHost()} 返回的是 TUN 虚拟网卡上的 {@code 198.18.0.1}——
     * 这个网段是 RFC 2544 的基准测试保留段，被 Clash/Surge 一类工具拿来占位，
     * **本机之外的任何机器都连不通**。把它注册进 Nacos 等于往注册表里写了一个死地址，
     * 比不注册更糟：消费者会拿到一个"看起来正常、连不上"的实例。
     *
     * <p>这里的选择是降级到 {@code 127.0.0.1}（本机演示场景下四个服务都在同一台机器），
     * 并打 WARN 提示跨机部署要显式配置 {@code NACOS_REGISTER_IP}。
     */
    private String resolveIp() throws Exception {
        if (!props.getIp().isBlank()) {
            return props.getIp();
        }
        String ip = InetAddress.getLocalHost().getHostAddress();
        if (isFakeIp(ip)) {
            log.warn("本机地址 {} 落在 198.18.0.0/15（代理 fake-ip / RFC2544 基准网段），"
                    + "不是可路由地址；本次注册用 127.0.0.1。跨机部署请显式设置 NACOS_REGISTER_IP", ip);
            return "127.0.0.1";
        }
        return ip;
    }

    /** 198.18.0.0/15 = 198.18.0.0 ~ 198.19.255.255。 */
    private static boolean isFakeIp(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length != 4 || !"198".equals(parts[0])) {
            return false;
        }
        try {
            int second = Integer.parseInt(parts[1]);
            return second == 18 || second == 19;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
