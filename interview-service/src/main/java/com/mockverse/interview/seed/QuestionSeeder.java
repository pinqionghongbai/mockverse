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
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 题库种子数据（D9 的 10 道八股题 + D19 的 3 道算法题）。
 *
 * <p>幂等: 按**题干**去重，缺哪道补哪道；重启不会重复灌数据，老库也能补上新题
 * （D19 加 ALGORITHM 题时必须这样，否则"表非空就跳过"会让新题永远进不去）。
 *
 * <p>D19 的三道 ALGORITHM 题带「输入样例 / 输出样例」，是判题闭环的题源——
 * 提示词只允许模型在题目原文给出标准输出时才传 {@code expectedOutput}，
 * 题干里不写样例，{@code judge_code} 就只能拿到 stdout、判不了对错。
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
    private final TransactionTemplate transactionTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            // 必须包事务：13 条 seed 是逐条 insert，中途失败会留下"半套题库"，
            // 而下次启动的去重判断会认为"已有数据"直接跳过 → 题库永久残缺且无告警。
            // 注意不能直接给 seedMissing() 加 @Transactional——同类内部调用不走代理，注解会失效
            // （巧合的是本类第 10 道种子题讲的正是这个知识点）。
            transactionTemplate.executeWithoutResult(status -> seedMissing());
        } catch (RuntimeException e) {
            // D15 收口后整改发现（Bug H）：MySQL 未就绪时**不能让整个进程退出**。
            // 此前 selectCount 直接抛出 → SpringApplication "Application run failed" → 进程退出，
            // 而 resume-service 在同样条件下能降级启动，两者可用性口径不一致。
            // 微服务由编排器拉起，启动顺序不保证（knowledge-service 对 pgvector 早就是降级语义）。
            // 代价：MySQL 恢复后不会自动补种，需重启本服务；期间题库为空时 start 返回 409。
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            log.warn("题库初始化失败，服务以降级状态启动（health 报 DEGRADED）: {}: {}。"
                    + "MySQL 恢复后需重启本服务才会补种种子数据",
                    root.getClass().getSimpleName(), root.getMessage());
        }
    }

    /**
     * 幂等灌库：**按题干去重**，缺哪道补哪道。
     *
     * <p><b>为什么从"表空才灌"改成"缺哪道补哪道"</b>（D19）：D9 时题库一次性灌 10 道，
     * "表非空就整体跳过"够用。D19 要加 ALGORITHM 题做判题闭环，而生产库里已有 10 行——
     * 老逻辑会直接跳过，新题永远进不去，判题闭环连题都抽不到。按题干去重后，
     * 老库补 3 道、新库灌 13 道，同一个方法覆盖两种状态，也不需要手工 SQL。
     *
     * <p>去重键选**题干**而不是新增唯一索引：题干是 VARCHAR(1000)，加唯一索引会超键长；
     * 而种子数据的题干是常量，改题干本就应该视为"新题"。代价是一次全表 select 的 content，
     * 启动期 13 行，可忽略。
     */
    private void seedMissing() {
        // 只在启动期执行一次（ApplicationRunner），单线程；用字段是为了让 13 处 seed(...)
        // 调用点保持一行一道题的写法，不必每处多传一个集合参数
        existingContents = questionMapper.selectList(
                        Wrappers.<Question>lambdaQuery().select(Question::getContent))
                .stream()
                .map(Question::getContent)
                .collect(Collectors.toCollection(HashSet::new));
        int before = existingContents.size();

        seedAll();

        log.info("题库种子数据: 已有 {} 道，本次新增 {} 道，合计 {} 道",
                before, seededCount, existingContents.size());
    }

    /** 启动期一次性状态（见 {@link #seedMissing()} 的说明）。 */
    private Set<String> existingContents = Set.of();
    private int seededCount = 0;

    private void seedAll() {
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

        // ---- ALGORITHM（D19）：判题闭环的题源 ----
        // 这三道题是**唯一带「输入样例 / 输出样例」的题**：提示词只允许模型在题目原文
        // 明确给了标准输出时才传 expectedOutput（D18 实测模型会自己编一个，导致
        // outputMatched 假阴性）。题干里不写样例，judge_code 就只能拿到 stdout、判不了对错。
        seed("ALGORITHM", "EASY",
                "用 Java 写一个程序：读入一行字符串，判断它是不是回文（区分大小写，只看字母和数字）。"
                        + "是则输出 true，否则输出 false。\n"
                        + "输入样例：abcba\n"
                        + "输出样例：true",
                "双指针从两端向中间比较，遇到不一致立即返回 false；跳过非字母数字字符。"
                        + "时间 O(n)、空间 O(1)。",
                "算法,回文,双指针");

        seed("ALGORITHM", "EASY",
                "用 Java 写一个程序：读入一个整数 n（1 ≤ n ≤ 40），输出斐波那契数列第 n 项"
                        + "（F(1)=1，F(2)=1）。\n"
                        + "输入样例：10\n"
                        + "输出样例：55",
                "迭代优于递归：递归是 O(2^n) 且 n=40 会明显变慢；用两个变量滚动即可 O(n)/O(1)。"
                        + "注意 n=1、n=2 的边界。",
                "算法,斐波那契,迭代");

        seed("ALGORITHM", "MEDIUM",
                "用 Java 写一个程序：第一行读入若干个空格分隔的整数，第二行读入目标值 target，"
                        + "输出两个下标 i < j，使得第 i 个数加第 j 个数等于 target（保证有且只有一组解）。"
                        + "下标从 0 开始，两个下标之间用一个空格分隔。\n"
                        + "输入样例：2 7 11 15\n9\n"
                        + "输出样例：0 1",
                "一次遍历 + HashMap 存「值 → 下标」：对每个 nums[i] 查 target-nums[i] 是否出现过，"
                        + "出现过就返回两个下标，否则把当前值放进去。时间 O(n)、空间 O(n)。"
                        + "暴力双重循环 O(n^2) 也能过小数据，面试时要主动说优化方向。",
                "算法,两数之和,哈希表");
    }

    private void seed(String category, String difficulty, String content, String answer, String tags) {
        // 已有同题干则跳过（D19：老库补题的关键）。插入后同步登记，防同一批种子内重复
        if (!existingContents.add(content)) {
            return;
        }
        Question q = new Question();
        q.setCategory(category);
        q.setDifficulty(difficulty);
        q.setContent(content);
        q.setReferenceAnswer(answer);
        q.setTags(tags);
        q.setEnabled(true);
        questionMapper.insert(q);
        seededCount++;
    }
}
