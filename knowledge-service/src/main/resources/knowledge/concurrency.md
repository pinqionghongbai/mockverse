# 并发编程语料

> MockVerse RAG 语料库 · 分类 CONCURRENCY · 共 6 篇

## synchronized 的锁升级

JDK6 起对 synchronized 做了大量优化，锁状态按竞争程度升级（不可逆）：

1. **无锁**。
2. **偏向锁**：第一个获取锁的线程把线程 ID 记在对象头 Mark Word 里，之后同一线程进入无需 CAS。JDK15 起默认禁用，JDK18 起移除。
3. **轻量级锁**：出现竞争时，用 CAS 把 Mark Word 替换为指向栈中锁记录的指针，失败则自旋。
4. **重量级锁**：自旋失败后膨胀为操作系统互斥量，线程进入阻塞队列，涉及用户态/内核态切换。

对象头 Mark Word 是锁状态的核心：64 位 JVM 下，无锁/偏向锁/轻量级锁/重量级锁对应不同的位模式。

## volatile 的两个语义

1. **可见性**：写操作立即刷新到主内存，读操作直接从主内存读，保证一个线程的修改对其它线程可见。
2. **有序性**：禁止指令重排序，通过内存屏障（LoadLoad / StoreStore / LoadStore / StoreLoad）实现。

**不保证原子性**：`i++` 是"读-改-写"三步，多线程下仍会丢失更新，需要 AtomicInteger 或加锁。

**典型用途**：状态标志位、双重检查锁定的单例（`private volatile static Singleton instance`，防止指令重排导致拿到未初始化完成的对象）。

## CAS 与 ABA 问题

CAS（Compare And Swap）包含三个操作数：内存值 V、预期值 A、新值 B。仅当 V == A 时才把 V 更新为 B，否则失败重试。底层由 CPU 的 `cmpxchg` 指令保证原子性。

**三个问题**：
1. **ABA 问题**：值从 A 改成 B 又改回 A，CAS 察觉不到。解决：加版本号（`AtomicStampedReference`）。
2. **自旋开销**：竞争激烈时大量空转消耗 CPU。
3. **只能保证单个变量的原子性**：多变量需 `AtomicReference` 包装或加锁。

Java 的 `AtomicInteger` 用 Unsafe 的 CAS 实现；JDK9 起推荐用 `VarHandle`。

## 线程池的核心参数与执行流程

`ThreadPoolExecutor` 七个参数：

- `corePoolSize`：核心线程数。
- `maximumPoolSize`：最大线程数。
- `keepAliveTime` + `unit`：非核心线程空闲存活时间。
- `workQueue`：任务队列。
- `threadFactory`：线程工厂（建议自定义线程名，便于排查）。
- `handler`：拒绝策略。

**执行流程**：核心线程未满 → 创建核心线程；核心线程满 → 入队列；队列满 → 创建非核心线程直到 maximum；仍满 → 触发拒绝策略。

**四种拒绝策略**：AbortPolicy（抛异常，默认）、CallerRunsPolicy（调用者线程执行）、DiscardPolicy（静默丢弃）、DiscardOldestPolicy（丢最老的）。

**为什么不用 Executors 的工厂方法**：`newFixedThreadPool` / `newSingleThreadExecutor` 用无界队列，可能 OOM；`newCachedThreadPool` 最大线程数是 Integer.MAX_VALUE，可能创建过多线程。阿里规约要求手动 new ThreadPoolExecutor。

## 线程池参数怎么定

**CPU 密集型**：线程数 ≈ CPU 核心数 + 1。

**IO 密集型**：线程数 ≈ CPU 核心数 × (1 + 平均等待时间 / 平均计算时间)。实践中常取核心数的 2 倍或更多。

**更可靠的做法**：压测。先按公式给初值，再用真实流量压测，观察队列长度、拒绝次数、CPU 使用率，逐步调整。

**队列选择**：有界队列（如 `ArrayBlockingQueue`）+ 明确的拒绝策略，避免无界队列掩盖问题。核心线程数可设 `allowCoreThreadTimeOut(true)` 让空闲核心线程也回收。

## AQS 的原理

AQS（AbstractQueuedSynchronizer）是 `ReentrantLock`、`Semaphore`、`CountDownLatch`、`ReentrantReadWriteLock` 的底层框架。

核心组成：
- **volatile int state**：同步状态，具体含义由子类定义（ReentrantLock 表示重入次数，Semaphore 表示可用许可数）。
- **CLH 变体的等待队列**：双向链表，节点用 `Node` 表示，未获取到锁的线程包装成节点入队并 park。
- **CAS 修改 state**：`tryAcquire` / `tryRelease` 由子类实现，AQS 负责排队与唤醒。

**公平锁与非公平锁**：公平锁在 `tryAcquire` 中先检查队列是否有前驱，有则排队；非公平锁直接 CAS 抢，抢不到再排队，吞吐更高但可能饥饿。

**ReentrantLock vs synchronized**：前者可中断（`lockInterruptibly`）、可超时（`tryLock(timeout)`）、可公平、支持多条件变量（`Condition`）；后者由 JVM 优化，代码更简洁。
