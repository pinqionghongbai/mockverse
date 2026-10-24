package com.mockverse.knowledge.rag;

/**
 * 向量库不可用（未启动、连不上、未建表）。
 *
 * <p>单独定义而不是复用 {@link IllegalStateException}，是为了让异常处理器能把它映射成
 * <b>503 Service Unavailable</b>——"依赖挂了"和"业务状态冲突"（409）是两回事，
 * 调用方据此决定是重试还是改参数。
 */
public class VectorStoreUnavailableException extends RuntimeException {

    public VectorStoreUnavailableException(String message) {
        super(message);
    }
}
