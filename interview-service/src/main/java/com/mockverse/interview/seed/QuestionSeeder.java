package com.mockverse.interview.seed;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mockverse.interview.entity.Question;
import com.mockverse.interview.mapper.QuestionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 题库种子数据（D9 的 10 道硬编码题）。
 *
 * <p>幂等: 只在 question 表为空时插入，重启不会重复灌数据。
 * D10 之后题库会扩充，届时这里保留为"最小可运行数据集"。
 *
 * <p><b>必须最先执行</b>: 其它 {@code ApplicationRunner}（面试冒烟）依赖题库非空，
 * 不加 {@code @Order} 时执行顺序不确定，实测会出现"种子还没跑、冒烟先查库"的失败。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class QuestionSeeder implements ApplicationRunner {

    private final QuestionMapper questionMapper;

    @Override
    public void run(ApplicationArguments args) {
        try {
            seedIfEmpty();
        } catch (RuntimeException e) {
            // D15 收口后整改（十查补测发现）：MySQL 未就绪时**不能让整个进程退出**。
            // 此前 selectCount 直接抛出 → SpringApplication "Application run failed" → 进程退出，
            // 而 resume-service 在同样条件下能降级启动，两者可用性口径不一致。
            // 微服务由编排器拉起，启动顺序不保证（knowledge-service 对 pgvector 早就是降级语义）。
            // 代价：MySQL 恢复后不会自动补种，需重启本服务；期间题库为空时 start 返回 409。
            log.warn("题库初始化失败，服务以降级状态启动（health 报 DEGRADED）: {}。"
                    + "MySQL 恢复后需重启本服务才会补种种子数据", e.getMessage());
        }
    }

    /** 幂等灌库。与 {@link #run} 分开，是为了让启动失败可被降级而不是打死进程。 */
    private void seedIfEmpty() {
        Long existing = questionMapper.selectCount(Wrappers.<Question>lambdaQuery());
        if (existing != null && existing > 0) {
            log.info("题库已有 {} 道题，跳过种子数据", existing);
            return;
        }

        seed("JAVA_BASIC", "MEDIUM",
                "HashMap 的扩容机制是怎样的？为什么负载因子默认是 0.75？",
                "JDK8 中 HashMap 默认容量 16、负载因子 0.75，元素数超过 容量×0.75 时扩容为原来的 2 倍。"
                        + "扩容时不需要重新计算 hash，因为容量是 2 的幂，元素新位置只需看 hash 与原容量按位与的结果："
                        + "为 0 留在原索引，为 1 则移到 原索引+原容量（JDK8 的高低链拆分，避免了 JDK7 头插导致的并发死循环）。"
                        + "0.75 是空间与时间的折中：太低浪费内存，太高冲突率上升。",
                "HashMap,扩容,负载因子");

        seed("JAVA_BASIC", "MEDIUM",
                "HashMap 中链表转红黑树的阈值为什么是 8，退回去为什么是 6？",
                "树化阈值 8 来自泊松分布：在负载因子 0.75 下，单个桶内元素数达到 8 的概率约为 0.00000006，"
                        + "正常情况下几乎不会树化，树化只是极端情况下的兜底（查询从 O(n) 降到 O(log n)）。"
                        + "退化阈值取 6 而不是 7，是为了留出缓冲区，避免元素在 7/8 之间反复增删导致频繁树化与退化。"
                        + "另外树化还要求容量 ≥ 64，否则优先扩容而不是树化。",
                "HashMap,红黑树,阈值");

        seed("JAVA_BASIC", "MEDIUM",
                "String 为什么设计成不可变？",
                "① 字符串常量池可以安全共享，相同字面量复用同一对象；② hash 值可以缓存，作为 HashMap 的 key 时性能稳定；"
                        + "③ 线程安全，无需同步；④ 安全性：类加载、数据库连接串等参数不会被中途篡改。"
                        + "不可变的代价是每次拼接都产生新对象，所以循环拼接要用 StringBuilder。",
                "String,不可变,常量池");

        seed("JVM", "HARD",
                "JVM 内存区域怎么划分？哪些是线程私有的？",
                "线程私有：程序计数器、虚拟机栈、本地方法栈。线程共享：堆、方法区（JDK8 起为元空间 Metaspace，使用本地内存）。"
                        + "堆是 GC 主战场，分新生代（Eden + 两个 Survivor）和老年代。"
                        + "程序计数器是唯一不会抛 OutOfMemoryError 的区域。",
                "JVM,内存模型,线程私有");

        seed("JVM", "HARD",
                "什么情况下对象会进入老年代？",
                "① 大对象直接进老年代（由 -XX:PretenureSizeThreshold 控制）；② 长期存活对象：每熬过一次 Minor GC 年龄 +1，"
                        + "达到 -XX:MaxTenuringThreshold（默认 15）晋升；③ 动态年龄判定：Survivor 中同龄对象总和超过一半，"
                        + "年龄 ≥ 该年龄的对象直接晋升；④ Minor GC 后 Survivor 放不下，通过分配担保进入老年代。",
                "JVM,GC,晋升");

        seed("MYSQL", "MEDIUM",
                "什么是联合索引的最左前缀原则？什么情况下会失效？",
                "联合索引 (a,b,c) 的 B+ 树先按 a 排序，a 相同再按 b，b 相同再按 c。"
                        + "查询条件必须从最左列开始连续匹配才能用上索引：WHERE a=? AND b=? 可用，WHERE b=? 不可用。"
                        + "常见失效场景：跳过最左列、对索引列使用函数或运算、隐式类型转换（字符串列传数字）、"
                        + "以 % 开头的 LIKE、OR 连接非索引列、范围查询之后的列无法继续用于排序。",
                "MySQL,索引,最左前缀");

        seed("MYSQL", "HARD",
                "InnoDB 的 MVCC 是怎么实现的？",
                "依赖三部分：① 隐藏字段：每行有 DB_TRX_ID（最后修改的事务 ID）和 DB_ROLL_PTR（指向 undo log 的版本链）；"
                        + "② undo log 版本链：每次修改都把旧值写入 undo log，形成可回溯的历史版本；"
                        + "③ ReadView：一致性读时生成，记录当前活跃事务 ID 集合，据此判断某个版本对当前事务是否可见。"
                        + "RC 隔离级别每条语句生成一次 ReadView，RR 只在事务首次查询时生成一次，这就是 RR 能避免不可重复读的原因。",
                "MySQL,MVCC,事务");

        seed("MYSQL", "MEDIUM",
                "为什么推荐使用自增主键而不是 UUID？",
                "InnoDB 使用聚簇索引，数据按主键顺序物理存储。自增主键保证插入总是追加到 B+ 树末尾，页分裂少、写入快；"
                        + "UUID 随机分布会导致频繁的页分裂和随机 IO，索引体积也更大（36 字节 vs 8 字节）。"
                        + "如果必须用分布式 ID，应选趋势递增的雪花 ID 而不是完全随机的 UUID。",
                "MySQL,主键,聚簇索引");

        seed("REDIS", "MEDIUM",
                "Redis 的过期删除策略和内存淘汰策略有什么区别？",
                "过期删除解决「key 到期了怎么删」：惰性删除（访问时才检查）+ 定期删除（每 100ms 随机抽查一批）。"
                        + "内存淘汰解决「内存满了删谁」：noeviction / allkeys-lru / allkeys-lfu / volatile-lru / volatile-ttl / random 等 8 种。"
                        + "两者是不同层次的问题：前者针对单个 key 的生命周期，后者针对整个实例的内存水位。",
                "Redis,过期,淘汰");

        seed("SPRING", "MEDIUM",
                "Spring 的 Bean 生命周期有哪些关键扩展点？",
                "实例化 → 属性填充（依赖注入）→ Aware 回调（BeanNameAware/BeanFactoryAware/ApplicationContextAware）"
                        + "→ BeanPostProcessor.postProcessBeforeInitialization → @PostConstruct / InitializingBean.afterPropertiesSet / init-method"
                        + "→ BeanPostProcessor.postProcessAfterInitialization（AOP 代理在这里生成）→ 使用 → @PreDestroy / DisposableBean.destroy。"
                        + "面试常问：AOP 代理是在 postProcessAfterInitialization 阶段织入的，所以 @Transactional 在同类内部调用会失效。",
                "Spring,Bean生命周期,AOP");

        log.info("题库种子数据导入完成: 10 道题");
    }

    private void seed(String category, String difficulty, String content, String answer, String tags) {
        Question q = new Question();
        q.setCategory(category);
        q.setDifficulty(difficulty);
        q.setContent(content);
        q.setReferenceAnswer(answer);
        q.setTags(tags);
        q.setEnabled(true);
        questionMapper.insert(q);
    }
}
