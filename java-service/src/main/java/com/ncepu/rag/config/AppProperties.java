package com.ncepu.rag.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 配置项脚手架（03 §9.1 的 Java 侧全量环境变量）。
 *
 * <p><b>必填项如何做到「缺失即启动失败」</b>：{@code application.yml} 中必填项一律写成
 * {@code ${VAR:}}（缺省为空串），由本类的 {@code @NotBlank} 拦截，消息直接写环境变量名。
 * 不能写成无默认值的 {@code ${VAR}}——Spring Boot 绑定配置属性时会忽略无法解析的占位符，
 * 变量缺失时得到字面量 {@code "${VAR}"}（非空串），校验会被绕过、服务带着假配置启动。
 * 该行为由 {@code scripts/verify-p01.sh} 第 5 步以真实进程验证。
 *
 * <p>安全类配置（HMAC / TLS / JWT）一律不做默认值，静默降级等于事故（03 §9 校验策略）。
 *
 * <p>数据库相关配置（{@code JAVA_DB_*}）在 P05 接入 MyBatis-Plus 时再绑定，P01 不引 JDBC 依赖，
 * 以免「骨架阶段就必须先起 MySQL 才能启动」。
 */
@ConfigurationProperties(prefix = "app")
@Validated
public record AppProperties(
        @Valid @NotNull Hmac hmac,
        @Valid @NotNull Rag rag,
        @Valid @NotNull Jwt jwt,
        @Valid @NotNull Sse sse,
        @Valid @NotNull Chat chat,
        @Valid @NotNull Cors cors,
        @Valid @NotNull Upload upload,
        @Valid @NotNull RateLimit rateLimit) {

    /** 与 Python 侧共享的服务间认证参数（NFR-04）。 */
    public record Hmac(
            @NotBlank(message = "缺少必填环境变量 HMAC_KEY_ID") String keyId,
            @NotBlank(message = "缺少必填环境变量 HMAC_SECRET") String secret,
            @Min(1) @Max(600) long clockSkewSeconds) {
    }

    /** Python rag-service 的连接参数（单机基线 127.0.0.1，UD-08）。 */
    public record Rag(
            @NotBlank(message = "缺少必填环境变量 RAG_HOST") String host,
            @Min(1) @Max(65535) int port,
            @Valid @NotNull Tls tls,
            @Valid @NotNull Timeouts timeouts) {

        /** NFR-03：一期只允许 TLS，禁止明文端口；{@code enabled=false} 不允许出现在配置里。 */
        public record Tls(
                boolean enabled,
                @NotBlank(message = "缺少必填环境变量 RAG_TLS_CA_PATH") String caPath,
                String overrideAuthority) {

            /**
             * NFR-03 的落地断言：{@code RAG_TLS_ENABLED} 只允许为 true。
             *
             * <p>只写注释约束不住配置——{@code --app.rag.tls.enabled=false} 会静默生效，
             * 服务带着明文 gRPC 跑起来，直到 P02 建 Channel 才发现。这里用 {@code @AssertTrue}
             * 让它和缺必填项一样在启动时失败。
             *
             * <p>注意：开关本身保留（而不是删掉这个字段），是为了让「置 false」报出**明确的
             * 变量名**，而不是变成一条看不懂的「未知配置项」。
             */
            @AssertTrue(message = "RAG_TLS_ENABLED 不得为 false：一期禁止明文 gRPC（NFR-03）")
            public boolean isEnabledRequired() {
                return enabled;
            }
        }

        /** 各类 RPC 的 deadline（建议值，待压测，见 03 §3.6）。 */
        public record Timeouts(
                @Min(1000) long chatMs,
                @Min(1000) long ingestMs,
                @Min(1000) long unaryMs) {
        }
    }

    /** JWT 签发参数（FR-01）。 */
    public record Jwt(
            @NotBlank(message = "缺少必填环境变量 JWT_SECRET") String secret,
            @Min(60) long expireSeconds,
            boolean blacklistEnabled) {
    }

    /** SSE 超时与心跳（03 §3.5 SSE-2/SSE-3）。 */
    public record Sse(
            @Min(1000) long timeoutMs,
            @Min(1000) long heartbeatMs) {
    }

    /** 问答入参约束（FR-23）。 */
    public record Chat(@Min(1) int questionMaxLen) {
    }

    /** CORS 白名单，禁止 {@code *} 与 credentials 共存（NFR-11）。 */
    public record Cors(@NotBlank(message = "缺少必填环境变量 CORS_ALLOWED_ORIGINS") String allowedOrigins) {
    }

    /** 文档上传约束（FR-08 / NFR-25）。 */
    public record Upload(
            @Min(1) long maxBytes,
            @NotBlank String allowedExt,
            @Min(1) long chunkBytes) {
    }

    /** 限流阈值（FR-42，建议值待确认 Q-14）。 */
    public record RateLimit(
            boolean enabled,
            @Min(1) int chatPerMin,
            @Min(1) int loginPerMin) {
    }
}
