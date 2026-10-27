package com.mockverse.knowledge.rag;

import com.mockverse.knowledge.config.VectorProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;

/** 知识库服务：加载语料 → 入库 → 检索。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final KnowledgeLoader loader;
    private final PgVectorStore store;
    private final VectorProperties properties;

    /**
     * 启动时自动灌语料（D29 修）。
     *
     * <p><b>为什么必须有这一条</b>：`ensureIndexed()` 之前**没有任何调用方**——它是个死方法。
     * 开发期语料是 D10 手工调 `POST /reindex` 灌进去的，而 `pg-data` 卷一直没删，
     * 所以问题一直没暴露。**换一台机器/新建卷，知识库就是空的**：RAG 对任何问题都答
     * "知识库未覆盖"，而且看起来像"检索没命中"，不像"库是空的"。
     *
     * <p><b>为什么挂在 {@code ApplicationReadyEvent} 而不是 {@code @PostConstruct}</b>：
     * 灌库要跑 50 条 ONNX 推理（数秒），放在 `@PostConstruct` 会拖慢 Tomcat 就绪、
     * 让健康检查超时。就绪后再灌，服务先能对外响应，灌完日志里能看到块数。
     *
     * <p><b>失败不阻断启动</b>：pg 没起时 `store.count()` 会抛，这里吞掉并告警——
     * 知识库不可用不该让整个服务起不来（与 `ResilientMcpToolset` 同一原则）。
     */
    @EventListener(ApplicationReadyEvent.class)
    void seedOnStartup() {
        try {
            int n = ensureIndexed();
            log.info("知识库就绪: {} 块", n);
        } catch (Exception e) {
            log.warn("启动灌语料失败（服务继续运行，可稍后调 POST /reindex）: {}", e.getMessage());
        }
    }

    /**
     * 幂等入库：库里已有数据就跳过。
     *
     * <p>不做"每次启动都重建"——ONNX 推理 50 条要几秒，正常启动不该背着这个成本。
     * 语料更新时显式调用 {@link #reindex()}。
     */
    public int ensureIndexed() {
        int existing = store.count();
        if (existing > 0) {
            log.info("向量库已有 {} 块，跳过入库", existing);
            return existing;
        }
        return reindex();
    }

    /** 强制全量重建。 */
    public int reindex() {
        List<KnowledgeChunk> chunks = loader.loadAll();
        long begin = System.currentTimeMillis();
        int n = store.replaceAll(chunks);
        log.info("全量重建耗时 {} ms", System.currentTimeMillis() - begin);
        return n;
    }

    public List<PgVectorStore.Hit> search(String query) {
        return search(query, properties.getTopK());
    }

    public List<PgVectorStore.Hit> search(String query, int topK) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("查询不能为空");
        }
        // 必须先判 available 再判 count: pg 停机时 count() 安静地返回 0，
        // 只看 count 会把"库挂了"误报成"库是空的"，用户按提示去入库，入库同样失败——
        // 错误消息把排查方向带偏（D12 收工二查抓出）
        if (!store.isAvailable()) {
            throw new VectorStoreUnavailableException("向量库不可用（pgvector 未启动或连接失败），见 STATUS.md 启动方式");
        }
        if (store.count() == 0) {
            throw new VectorStoreUnavailableException("向量库为空，请先执行入库");
        }
        return store.search(query, topK);
    }
}
