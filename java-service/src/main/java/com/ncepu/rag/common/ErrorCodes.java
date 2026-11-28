package com.ncepu.rag.common;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/**
 * 业务错误码（03 §8.2 的子集，P02 只落地与 gRPC 通信相关的部分）。
 *
 * <p>错误码分段：1xxx 业务校验、2xxx 文档、3xxx RAG 服务、4xxx 输入、5xxx 系统。
 * 新增码必须先在 03 §8.2 登记，否则前端拿不到对应的提示文案。
 */
public final class ErrorCodes {

    /** RAG 服务不可用（连不上 / Python 没起来）。HTTP 503。 */
    public static final int RAG_UNAVAILABLE = 3001;

    /** RAG 调用超时（DEADLINE_EXCEEDED）。HTTP 504。 */
    public static final int RAG_TIMEOUT = 3002;

    /** 内部服务认证失败（HMAC 验签未通过）。HTTP 500，P13 启用。 */
    public static final int RAG_AUTH_FAILED = 3004;

    /** 未分类的内部错误。HTTP 500。 */
    public static final int INTERNAL_ERROR = 5001;

    private ErrorCodes() {
    }

    /** 错误码 → HTTP 状态码（03 §8.2）。未登记的码一律按 500 处理。 */
    public static int httpStatus(int code) {
        return switch (code) {
            case RAG_UNAVAILABLE -> 503;
            case RAG_TIMEOUT -> 504;
            default -> 500;
        };
    }

    /**
     * gRPC 状态 → 业务错误码。
     *
     * <p><b>3001 与 3004 不能混用</b>：3001 是「连不上」（网络/进程级，重试通常有效），
     * 3004 是「连上了但认证没过」（配置/时钟问题，盲目重试无效）。两者运维动作完全不同，
     * 合并成一个码会让排障方向直接跑偏。
     */
    public static int fromGrpcStatus(Status status) {
        if (status == null) {
            return INTERNAL_ERROR;
        }
        return switch (status.getCode()) {
            case UNAVAILABLE -> RAG_UNAVAILABLE;
            case DEADLINE_EXCEEDED -> RAG_TIMEOUT;
            case UNAUTHENTICATED, PERMISSION_DENIED -> RAG_AUTH_FAILED;
            default -> INTERNAL_ERROR;
        };
    }

    /** 从异常提取 gRPC 状态；非 gRPC 异常返回 null。 */
    public static Status statusOf(Throwable throwable) {
        if (throwable instanceof StatusRuntimeException sre) {
            return sre.getStatus();
        }
        return Status.fromThrowable(throwable);
    }

    /** 异常 → 业务错误码（P02 验收判据③要求 UNAVAILABLE 映射到 3001）。 */
    public static int fromThrowable(Throwable throwable) {
        return fromGrpcStatus(statusOf(throwable));
    }
}
