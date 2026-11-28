package com.ncepu.rag.client;

import com.ncepu.rag.RagApplication;
import com.ncepu.rag.grpc.ChatChunk;
import com.ncepu.rag.grpc.ChatEventType;
import com.ncepu.rag.grpc.ChatRequest;
import com.ncepu.rag.grpc.HealthResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P02 集成用例：连**真实运行**的 Python rag-service（TLS）。
 *
 * <p>为什么必须真连而不是 mock：P02 的验收目标就是「TLS 两端能握手、流式帧能落地」。
 * mock 掉 Channel 后，证书 SAN、TLS 版本、HTTP/2 协商这些真正会出问题的地方全被绕过。
 *
 * <p>启用方式（由 {@code scripts/verify-p02.sh} 负责拉起服务并传参）：
 * <pre>
 * mvn test -Dp02.it=true -Dp02.host=127.0.0.1 -Dp02.port=50551 -Dp02.ca=deploy/certs/ca.crt
 * </pre>
 * 没传 {@code -Dp02.it=true} 时整类跳过，保证 `mvn test` 在无服务环境下仍是绿的。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "p02.it", matches = "true")
class RagServiceClientIT {

    private ConfigurableApplicationContext context;
    private RagServiceClient client;

    @BeforeAll
    void startContext() {
        String host = System.getProperty("p02.host", "127.0.0.1");
        String port = System.getProperty("p02.port", "50551");
        String ca = System.getProperty("p02.ca");

        assertThat(ca).as("必须通过 -Dp02.ca 指定 CA 证书").isNotBlank();

        context = new SpringApplicationBuilder(RagApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--app.hmac.key-id=k1",
                        "--app.hmac.secret=dummy-secret-for-it",
                        "--app.jwt.secret=0123456789abcdef0123456789abcdef",
                        "--app.cors.allowed-origins=http://localhost:5173",
                        "--app.rag.host=" + host,
                        "--app.rag.port=" + port,
                        "--app.rag.tls.ca-path=" + ca,
                        "--app.rag.timeouts.chat-ms=30000",
                        "--app.rag.timeouts.unary-ms=5000"
                );
        client = context.getBean(RagServiceClient.class);
    }

    @AfterAll
    void stopContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    @DisplayName("P02 判据①：Java 能连上 Python 并拿到 healthy=true")
    void healthCheckOverTls() {
        HealthResponse response = client.health("it-trace-1");

        assertThat(response.getHealthy()).isTrue();
        assertThat(response.getService()).isEqualTo("rag-service");
        assertThat(response.getVersion()).isNotBlank();
        assertThat(response.getTraceId()).isEqualTo("it-trace-1");
        assertThat(response.getTimestampMs()).isPositive();
    }

    @Test
    @DisplayName("test_chat_stream_frames：≥2 个 CONTENT 帧 + 1 个 FINISHED，seq 连续")
    void chatStreamFrames() {
        ChatRequest request = ChatRequest.newBuilder()
                .setUserId("u1")
                .setSessionId("s1")
                .setQuestion("转专业需要什么条件？")
                .setTraceId("it-trace-2")
                .build();

        List<ChatChunk> chunks = new ArrayList<>();
        Iterator<ChatChunk> it = client.chat(request);
        while (it.hasNext()) {
            chunks.add(it.next());
        }

        assertThat(chunks).as("至少 2 个 CONTENT + 1 个 FINISHED").hasSizeGreaterThanOrEqualTo(3);

        long contentCount = chunks.stream()
                .filter(c -> c.getEventType() == ChatEventType.CHAT_EVENT_CONTENT)
                .count();
        assertThat(contentCount).as("验收要求至少 2 个 CONTENT 帧").isGreaterThanOrEqualTo(2);

        ChatChunk last = chunks.get(chunks.size() - 1);
        assertThat(last.getEventType()).isEqualTo(ChatEventType.CHAT_EVENT_FINISHED);
        assertThat(last.getFinished()).isTrue();

        // seq 必须从 1 连续递增：前端靠它做乱序容错与丢帧检测
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).getSeq()).as("第 %d 帧的 seq", i + 1).isEqualTo(i + 1);
        }

        assertThat(chunks).allMatch(c -> c.getTraceId().equals("it-trace-2"),
                "trace_id 必须全程回显一致");
    }
}
