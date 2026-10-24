package com.mockverse.interview.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 把业务异常映射成合理的 HTTP 状态码。
 *
 * <p>没有它的话，参数错误和"会话已结束"都会变成 500——调用方分不清是自己传错了
 * 还是服务挂了。用 Spring 6 的 {@link ProblemDetail}（RFC 7807），前端能拿到
 * 稳定的 {@code type/title/detail} 结构。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** 会话已结束、题库为空这类"当前状态不允许"的错误。 */
    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail conflict(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    // 503 (VectorStoreUnavailableException) handler 不在本服务副本里: rag 包已随 D15
    // 拆分迁往 knowledge-service, 这里没有这个异常类型。
    // 刻意不写 @ExceptionHandler(Exception.class)：那会把 404（NoResourceFoundException）、
    // 405 等框架异常也一并吞成 500。未预期异常交给 Spring 默认处理即可。
}
