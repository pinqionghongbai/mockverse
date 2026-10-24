package com.mockverse.knowledge.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15.BgeSmallZhV15EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Embedding 模型（D10）。
 *
 * <p><b>为什么用本地 ONNX 而不是调 API</b>：
 * <ul>
 *   <li>DeepSeek 没有 {@code /embeddings} 接口（实测返回 404），不能沿用 D9 的模型供应商</li>
 *   <li>Ollama 拉模型被本机代理的 fake-ip DNS 卡在 pulling manifest（见 docs/ollama-issue.md）</li>
 *   <li>本地 ONNX 模型随 jar 分发，不联网、不需要第二个 API Key、离线可复现</li>
 * </ul>
 *
 * <p>代价：启动时要把 50MB 的模型加载进内存，首次 embed 有几百毫秒预热；CPU 推理比 API 慢，
 * 但 50 条语料的批量入库只是一次性的几秒钟，可以接受。
 */
@Configuration
public class EmbeddingModelConfig {

    @Bean
    public EmbeddingModel embeddingModel() {
        return new BgeSmallZhV15EmbeddingModel();
    }
}
