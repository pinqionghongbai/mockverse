package com.mockverse.interview.nacos;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.AbstractListener;
import com.mockverse.interview.config.InterviewProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.util.Map;
import java.util.Properties;

/**
 * Nacos 动态配置：让"追问轮数"改完不重启就生效（D23）。
 *
 * <p><b>改的是哪个值</b>：{@code mockverse.interview.max-rounds}。
 * 它只在 {@code InterviewService.start()} 里被读一次，写进 {@code interview_session.max_rounds}；
 * 之后的 {@code answer()} 用的是**会话行里的值**，不是配置里的值。
 * 所以语义是"**新开的会话**立刻用新轮数，已在进行的会话不受影响"——这是有意的：
 * 面试中途改上限会让"第 5 轮已结束"的会话变得自相矛盾。
 *
 * <p><b>为什么用 YAML 而不是 properties</b>：Nacos 控制台默认新建的就是 yaml，
 * 演示时不用额外解释格式。SnakeYAML 由 spring-boot-starter 自带，没有新增依赖。
 *
 * <p><b>为什么要在监听里做类型/范围校验</b>：Nacos 是外部可写的，写进一个
 * {@code max-rounds: 0} 或 {@code max-rounds: abc} 会让所有新会话立刻不可用。
 * 解析失败一律保留旧值并 WARN，不抛异常——配置中心的坏值不该让进程崩。
 */
@Slf4j
@Component
public class NacosConfigRefresher {

    /** 配置键。写死在常量里而不是再开一个属性：它就是本类存在的唯一理由。 */
    private static final String KEY_MAX_ROUNDS = "max-rounds";

    private static final int MIN_ROUNDS = 1;
    private static final int MAX_ROUNDS = 50;

    private final NacosProperties props;
    private final InterviewProperties interviewProperties;
    private final String appName;

    private ConfigService configService;

    public NacosConfigRefresher(NacosProperties props,
                                InterviewProperties interviewProperties,
                                @Value("${spring.application.name}") String appName) {
        this.props = props;
        this.interviewProperties = interviewProperties;
        this.appName = appName;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void subscribe() {
        if (!props.isEnabled()) {
            return;
        }
        String dataId = props.getConfigDataId().isBlank()
                ? appName + ".yaml"
                : props.getConfigDataId();
        try {
            Properties cfg = new Properties();
            cfg.put("serverAddr", props.getServerAddr());
            if (!props.getNamespace().isBlank()) {
                cfg.put("namespace", props.getNamespace());
            }
            configService = NacosFactory.createConfigService(cfg);

            // 先拉一次现值：服务启动时 Nacos 上已有的配置要立刻生效，不能等第一次变更。
            // 注意 Nacos 宕机时 getConfig 走的是**本地快照**（客户端把上次拉到的内容缓存在
            // ~/nacos/config/），所以这里可能拿到"上次的配置"而不是 null——这是特性不是 bug，
            // D23 实测：Nacos 停着启动，拉到的是快照值 5；Nacos 恢复后变更推送照常到达。
            apply(configService.getConfig(dataId, props.getGroup(), 3000), "启动拉取");

            configService.addListener(dataId, props.getGroup(), new AbstractListener() {
                @Override
                public void receiveConfigInfo(String configInfo) {
                    apply(configInfo, "配置变更推送");
                }
            });
            // 用 getServerStatus() 而不是无条件说"已订阅"：Nacos 宕机时 addListener 仍然成功
            // （监听器是本地注册的），此时报"已订阅"会让人以为连接是好的。
            log.info("已订阅 Nacos 动态配置: dataId={}, group={}, serverStatus={}（改 max-rounds 后新会话即时生效）",
                    dataId, props.getGroup(), configService.getServerStatus());
        } catch (Exception e) {
            log.warn("订阅 Nacos 动态配置失败，max-rounds 固定为本地值 {}: {}: {}",
                    interviewProperties.getMaxRounds(), e.getClass().getSimpleName(), e.getMessage());
        }
    }

    @EventListener(ContextClosedEvent.class)
    public void unsubscribe() {
        if (configService == null) {
            return;
        }
        try {
            configService.shutDown();
        } catch (Exception e) {
            log.debug("关闭 Nacos ConfigService 时出错（进程即将退出，可忽略）: {}", e.getMessage());
        }
    }

    /**
     * 把一份 YAML 配置应用到 {@link InterviewProperties}。
     *
     * <p>结构必须是 {@code mockverse.interview.max-rounds}（与本地 application.yml 同构），
     * 这样同一段配置既能放 Nacos 也能放本地文件，不会出现"两份配置语义不同"。
     */
    void apply(String yamlText, String source) {
        if (yamlText == null || yamlText.isBlank()) {
            log.debug("Nacos 配置为空（{}），保留本地值 max-rounds={}", source,
                    interviewProperties.getMaxRounds());
            return;
        }
        Object raw;
        try {
            raw = new Yaml().load(yamlText);
        } catch (RuntimeException e) {
            log.warn("Nacos 配置不是合法 YAML（{}），保留旧值 {}: {}", source,
                    interviewProperties.getMaxRounds(), e.getMessage());
            return;
        }
        Object value = dig(raw, "mockverse", "interview", KEY_MAX_ROUNDS);
        if (value == null) {
            log.debug("Nacos 配置里没有 mockverse.interview.max-rounds（{}），保留旧值", source);
            return;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            log.warn("Nacos 的 max-rounds 不是整数（{}）: {}，保留旧值 {}", source, value,
                    interviewProperties.getMaxRounds());
            return;
        }
        if (parsed < MIN_ROUNDS || parsed > MAX_ROUNDS) {
            log.warn("Nacos 的 max-rounds={} 超出 [{}, {}]（{}），保留旧值 {}", parsed, MIN_ROUNDS,
                    MAX_ROUNDS, source, interviewProperties.getMaxRounds());
            return;
        }
        int old = interviewProperties.getMaxRounds();
        interviewProperties.setMaxRounds(parsed);
        if (old == parsed) {
            // 值没变就别打 INFO：启动拉取时"5 -> 5"是常态，会把真正的变更淹掉
            log.debug("Nacos 的 max-rounds 与当前值一致（{}），无需变更", parsed);
            return;
        }
        log.info("动态配置生效（{}）: max-rounds {} -> {}（对**新开的会话**立即生效，进行中的会话不受影响）",
                source, old, parsed);
    }

    /** 逐层下钻 Map；任一层不是 Map 或键不存在都返回 null。 */
    private static Object dig(Object root, String... path) {
        Object current = root;
        for (String key : path) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(key);
        }
        return current;
    }
}
