package com.ncepu.rag.common;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P02 用例 {@code test_grpc_error_mapping}（只覆盖本期相关的一条：UNAVAILABLE → 3001）。
 *
 * <p>这个映射看起来"就是查个表"，但它是**用户看到什么提示**的决定因素：
 * 映射错了，前端会把"Python 没起来"说成"AI 服务异常"，把运维方向带偏。
 */
class GrpcStatusMapperTest {

    @Test
    @DisplayName("test_grpc_error_mapping: UNAVAILABLE 必须映射到 3001 / HTTP 503")
    void unavailableMapsTo3001() {
        Throwable failure = Status.UNAVAILABLE
                .withDescription("io exception")
                .asRuntimeException();

        int code = ErrorCodes.fromThrowable(failure);

        assertThat(code).isEqualTo(ErrorCodes.RAG_UNAVAILABLE);
        assertThat(ErrorCodes.httpStatus(code)).isEqualTo(503);
    }

    @Test
    @DisplayName("test_grpc_error_mapping: DEADLINE_EXCEEDED 映射到 3002 / HTTP 504")
    void deadlineExceededMapsTo3002() {
        Throwable failure = Status.DEADLINE_EXCEEDED.asRuntimeException();

        int code = ErrorCodes.fromThrowable(failure);

        assertThat(code).isEqualTo(ErrorCodes.RAG_TIMEOUT);
        assertThat(ErrorCodes.httpStatus(code)).isEqualTo(504);
    }

    @Test
    @DisplayName("test_grpc_error_mapping: UNAUTHENTICATED 映射到 3004（与 3001 不能混用）")
    void unauthenticatedMapsTo3004() {
        Throwable failure = new StatusRuntimeException(Status.UNAUTHENTICATED);

        int code = ErrorCodes.fromThrowable(failure);

        assertThat(code)
                .as("3001=连不上、3004=连上了但认证失败，运维动作完全不同，不能合并")
                .isEqualTo(ErrorCodes.RAG_AUTH_FAILED);
        assertThat(ErrorCodes.httpStatus(code)).isEqualTo(500);
    }

    @Test
    @DisplayName("test_grpc_error_mapping: 未登记的状态兜底为 5001，不抛异常")
    void unknownStatusFallsBackTo5001() {
        Throwable failure = Status.UNKNOWN.asRuntimeException();

        assertThat(ErrorCodes.fromThrowable(failure)).isEqualTo(ErrorCodes.INTERNAL_ERROR);
        assertThat(ErrorCodes.httpStatus(ErrorCodes.INTERNAL_ERROR)).isEqualTo(500);
    }

    @Test
    @DisplayName("test_grpc_error_mapping: 非 gRPC 异常也兜底为 5001")
    void nonGrpcExceptionFallsBack() {
        assertThat(ErrorCodes.fromThrowable(new IllegalStateException("boom")))
                .isEqualTo(ErrorCodes.INTERNAL_ERROR);
    }
}
