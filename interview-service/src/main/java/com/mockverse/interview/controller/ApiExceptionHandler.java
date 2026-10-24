package com.mockverse.interview.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
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
@Slf4j
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

    /**
     * 数据访问异常：**必须分类**，不能一律 503。
     *
     * <p>D15 八查实测：MySQL 宕机时 {@code /api/interview/start} 返回 500 通用体，
     * 调用方分不清"服务挂了"还是"我传错了"。但直接
     * {@code @ExceptionHandler(DataAccessException.class)} → 503 也是错的：
     * 雪花 ID 撞主键（{@link DataIntegrityViolationException}）会被误报成"服务不可用"。
     *
     * <p>所以按**根因类型**分流（连接类 → 503，约束类 → 409，其余 → 500）。
     * 注意 {@code MyBatisSystemException} 本身是 {@code UncategorizedDataAccessException}，
     * 真正的连接失败藏在 cause 链末端（{@code java.net.ConnectException}），必须走链。
     */
    @ExceptionHandler(DataAccessException.class)
    public ProblemDetail dataAccess(DataAccessException e) {
        if (isConnectivityFailure(e)) {
            // 细节只进日志，不进响应体：错误体是外部可见的。
            // 注意 MyBatisSystemException.getMessage() 是 null，必须取根因才有信息量。
            Throwable root = rootCause(e);
            log.warn("数据库不可用: {}: {}", root.getClass().getSimpleName(), root.getMessage());
            return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                    "数据库暂时不可用，请稍后重试");
        }
        if (e instanceof DataIntegrityViolationException) {
            log.warn("数据约束冲突: {}", e.getMostSpecificCause().getMessage());
            return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                    "数据约束冲突（主键或唯一索引重复）");
        }
        log.error("未归类的数据访问失败", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "数据访问失败: " + e.getClass().getSimpleName());
    }

    /** 取最内层 cause：MyBatis 的包装异常 message 常为 null，直接打外层没有信息量。 */
    private static Throwable rootCause(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t;
    }

    /**
     * 沿 cause 链找连接类异常。
     *
     * <p>用 JDK 自带的类型判定，不 import 驱动/Hikari 的异常类——那会把错误处理
     * 和具体连接池实现绑死（knowledge-service 用 Hikari 直连 pg，这里用的是
     * Spring 自动配置的池，两者实现可以换）。
     */
    private static boolean isConnectivityFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.ConnectException
                    || t instanceof java.net.UnknownHostException
                    || t instanceof java.net.SocketTimeoutException
                    || t instanceof java.sql.SQLTransientConnectionException
                    || t instanceof java.sql.SQLNonTransientConnectionException
                    || t instanceof java.sql.SQLRecoverableException) {
                return true;
            }
            if (t == t.getCause()) {
                break;  // 防御自环，避免死循环
            }
        }
        return false;
    }

    // 刻意不写 @ExceptionHandler(Exception.class)：那会把 404（NoResourceFoundException）、
    // 405 等框架异常也一并吞成 500。未预期异常交给 Spring 默认处理即可。
}
