package com.ncepu.rag.client;

import com.ncepu.rag.RagApplication;
import com.ncepu.rag.common.ErrorCodes;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * P02 用例 {@code e2e_rag_down}：**Python 停机后** Java 侧必须得到 UNAVAILABLE，
 * 并且映射为业务码 3001（不是 3004，也不是 5001）。
 *
 * <p>验证方式：{@code scripts/verify-p02.sh} 先把 Python 服务停掉，再以
 * {@code -Dp02.down=true} 跑本用例。这样测的是「进程真的没了」的场景，
 * 而不是在测试里 mock 一个异常——后者只能证明 catch 块写得对。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "p02.down", matches = "true")
class RagServiceDownIT {

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
                        // 对端已停机，超时给短一点，别让用例卡 5 秒
                        "--app.rag.timeouts.unary-ms=2000"
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
    @DisplayName("e2e_rag_down：Python 停机时 HealthCheck 抛 UNAVAILABLE 且映射为 3001")
    void healthCheckFailsWhenRagDown() {
        Throwable failure = catchThrowable(() -> client.health("down-trace"));

        assertThat(failure).as("对端停机时调用必须抛异常，而不是返回一个假的 healthy").isNotNull();
        assertThat(failure).isInstanceOf(StatusRuntimeException.class);

        StatusRuntimeException sre = (StatusRuntimeException) failure;
        assertThat(sre.getStatus().getCode())
                .as("服务没起来应表现为 UNAVAILABLE（连接级问题）")
                .isEqualTo(Status.Code.UNAVAILABLE);

        assertThat(ErrorCodes.fromThrowable(failure))
                .as("UNAVAILABLE 必须映射到 3001，不能落到 3004（认证失败）或 5001")
                .isEqualTo(ErrorCodes.RAG_UNAVAILABLE);
        assertThat(ErrorCodes.httpStatus(ErrorCodes.RAG_UNAVAILABLE)).isEqualTo(503);
    }
}
