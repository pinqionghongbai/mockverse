package com.mockverse.interview.cache;

/**
 * SSE 限流拒绝（D23）。
 *
 * <p>单独一个异常类型而不是复用 {@code IllegalStateException}：后者在
 * {@code ApiExceptionHandler} 里映射成 409（"当前状态不允许"），而限流该是
 * <b>429 Too Many Requests</b>——调用方据此知道"等一会儿重试"而不是"改参数"。
 */
public class RateLimitExceededException extends RuntimeException {

    public RateLimitExceededException(String message) {
        super(message);
    }
}
