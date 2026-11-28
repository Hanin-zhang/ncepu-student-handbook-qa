package com.ncepu.rag.client;

import com.ncepu.rag.config.AppProperties;
import com.ncepu.rag.grpc.ChatChunk;
import com.ncepu.rag.grpc.ChatRequest;
import com.ncepu.rag.grpc.HealthRequest;
import com.ncepu.rag.grpc.HealthResponse;
import com.ncepu.rag.grpc.RagServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.concurrent.TimeUnit;

/**
 * rag-service 客户端门面（P02：HealthCheck + Chat 两条链路）。
 *
 * <p>为什么包一层而不是让业务直接拿 Stub：deadline 必须统一设置。
 * gRPC 不设 deadline 时，对端进程卡死会让调用线程无限期挂住——P09 的 SSE 转发
 * 一旦挂住就是「无限 loading」。把 deadline 收在门面里，业务代码不可能忘。
 */
@Component
public class RagServiceClient {

    private final RagServiceGrpc.RagServiceBlockingStub blockingStub;
    private final RagServiceGrpc.RagServiceStub asyncStub;
    private final long unaryTimeoutMs;
    private final long chatTimeoutMs;

    public RagServiceClient(ManagedChannel channel, AppProperties props) {
        this.blockingStub = RagServiceGrpc.newBlockingStub(channel);
        this.asyncStub = RagServiceGrpc.newStub(channel);
        this.unaryTimeoutMs = props.rag().timeouts().unaryMs();
        this.chatTimeoutMs = props.rag().timeouts().chatMs();
    }

    /** 健康检查（同步，短超时）。traceId 可为空，服务端会自行补一个。 */
    public HealthResponse health(String traceId) {
        HealthRequest.Builder req = HealthRequest.newBuilder();
        if (traceId != null && !traceId.isBlank()) {
            req.setTraceId(traceId);
        }
        return blockingStub
                .withDeadlineAfter(unaryTimeoutMs, TimeUnit.MILLISECONDS)
                .healthCheck(req.build());
    }

    /**
     * 同步遍历 Chat 的流式返回。P02 的冒烟与集成测试用它；
     * P09 的 SSE 转发会用下面的异步版本，避免占用调用线程。
     */
    public Iterator<ChatChunk> chat(ChatRequest request) {
        return blockingStub
                .withDeadlineAfter(chatTimeoutMs, TimeUnit.MILLISECONDS)
                .chat(request);
    }

    /** 异步流式调用：返回即开始接收，帧通过 observer 回调。 */
    public void chatAsync(ChatRequest request, StreamObserver<ChatChunk> observer) {
        asyncStub
                .withDeadlineAfter(chatTimeoutMs, TimeUnit.MILLISECONDS)
                .chat(request, observer);
    }
}
