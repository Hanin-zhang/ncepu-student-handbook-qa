package com.ncepu.rag.config;

import com.ncepu.rag.RagApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * P01 用例 {@code test_config_required_missing}（Java 侧）。
 *
 * <p>判定标准（03 §10.1 / 04 P01）：必填变量缺失时**启动即失败**，且失败信息里能读到变量名；
 * 不做「缺了就静默用默认值」的容错。
 *
 * <p>用命令行参数而非真实环境变量注入，原因：命令行参数优先级最高，测试结果不会被
 * 开发机或 CI 上残留的同名环境变量改变。
 */
class AppPropertiesStartupTest {

    /**
     * 环境变量名 → 配置属性名的映射，用于「逐个必填项缺失」的参数化用例。
     *
     * <p>新增必填项时这里也要登记，否则 {@code everyRequiredVariableMissingFailsFast}
     * 会因缺少映射直接失败——这是有意的：验收口径是「每个必填项缺失都要能报出变量名」。
     */
    private static final Map<String, String> REQUIRED_ENV_TO_PROPERTY = createRequiredMapping();

    private static Map<String, String> createRequiredMapping() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("HMAC_KEY_ID", "app.hmac.key-id");
        map.put("HMAC_SECRET", "app.hmac.secret");
        map.put("JWT_SECRET", "app.jwt.secret");
        map.put("RAG_HOST", "app.rag.host");
        map.put("RAG_TLS_CA_PATH", "app.rag.tls.ca-path");
        map.put("CORS_ALLOWED_ORIGINS", "app.cors.allowed-origins");
        return Map.copyOf(map);
    }

    /** 全部必填项都给齐的命令行参数（参数化用例按需移除其中一项）。 */
    private static List<String> allRequiredArgs() {
        List<String> args = new ArrayList<>();
        args.add("--server.port=0");
        args.add("--app.hmac.key-id=k1");
        args.add("--app.hmac.secret=dummy-secret-for-test");
        args.add("--app.jwt.secret=0123456789abcdef0123456789abcdef");
        args.add("--app.rag.host=127.0.0.1");
        args.add("--app.rag.tls.ca-path=/tmp/rag-ca.pem");
        args.add("--app.cors.allowed-origins=http://localhost:5173");
        return args;
    }

    /**
     * 公共参数：只放「本次测试不打算验证缺失」的必填项。
     *
     * <p>注意不要把某个变量同时写两遍（例如先给非空再给空）：命令行参数允许同名重复，
     * Spring 会把多个值合并，空值不会覆盖先前的非空值，测试就会假绿。
     */
    private static List<String> baseArgs() {
        List<String> args = new ArrayList<>();
        args.add("--server.port=0");
        args.add("--app.hmac.key-id=k1");
        args.add("--app.rag.host=127.0.0.1");
        args.add("--app.rag.tls.ca-path=/tmp/rag-ca.pem");
        args.add("--app.cors.allowed-origins=http://localhost:5173");
        return args;
    }

    private static SpringApplication nonWebApp() {
        SpringApplication app = new SpringApplication(RagApplication.class);
        // 不起 Web 服务器：P01 只验证配置绑定与校验，端口占用会干扰本机并行测试
        app.setWebApplicationType(WebApplicationType.NONE);
        return app;
    }

    @Test
    @DisplayName("test_config_required_missing: HMAC_SECRET 为空时启动失败且报出变量名")
    void missingHmacSecretFailsFastWithVariableName() {
        List<String> args = baseArgs();
        args.add("--app.hmac.secret=");
        args.add("--app.jwt.secret=0123456789abcdef0123456789abcdef");

        Throwable failure = catchThrowable(() -> nonWebApp().run(args.toArray(String[]::new)));

        assertThat(failure).as("缺少 HMAC_SECRET 时必须启动失败").isNotNull();
        assertThat(allMessages(failure)).contains("HMAC_SECRET");
    }

    @ParameterizedTest(name = "缺 {0} 时启动失败并报出变量名")
    @ValueSource(strings = {
            "HMAC_KEY_ID",
            "HMAC_SECRET",
            "JWT_SECRET",
            "RAG_HOST",
            "RAG_TLS_CA_PATH",
            "CORS_ALLOWED_ORIGINS"})
    @DisplayName("test_config_required_missing: 每个必填项缺失都必须启动失败并报出变量名")
    void everyRequiredVariableMissingFailsFast(String envName) {
        // 回归用例：曾因 ${VAR} 无默认值，变量缺失时被绑定成字面量 "${VAR}" 而绕过 @NotBlank。
        // 本机若恰好设置了同名环境变量，「缺失」场景无法构造，跳过而不是误报。
        assumeTrue(System.getenv(envName) == null,
                "本机已设置环境变量 " + envName + "，无法构造缺失场景");

        String property = REQUIRED_ENV_TO_PROPERTY.get(envName);
        assertThat(property).as("环境变量 %s 必须登记到 REQUIRED_ENV_TO_PROPERTY", envName).isNotNull();

        // 只移除被测项，其余必填项都给齐，确保失败原因唯一
        String prefix = "--" + property + "=";
        List<String> args = allRequiredArgs().stream()
                .filter(a -> !a.startsWith(prefix))
                .collect(Collectors.toList());
        assertThat(args).as("装配错误：被测项仍出现在命令行参数里").noneMatch(a -> a.startsWith(prefix));

        Throwable failure = catchThrowable(() -> nonWebApp().run(args.toArray(String[]::new)));

        assertThat(failure).as("缺少 %s 时必须启动失败", envName).isNotNull();
        assertThat(allMessages(failure)).as("失败信息必须包含变量名 %s", envName).contains(envName);
    }

    @Test
    @DisplayName("test_config_required_missing: JWT_SECRET 为空时启动失败且报出变量名")
    void missingJwtSecretFailsFastWithVariableName() {
        List<String> args = baseArgs();
        args.add("--app.hmac.secret=dummy-secret-for-test");
        args.add("--app.jwt.secret=");

        Throwable failure = catchThrowable(() -> nonWebApp().run(args.toArray(String[]::new)));

        assertThat(failure).as("缺少 JWT_SECRET 时必须启动失败").isNotNull();
        assertThat(allMessages(failure)).contains("JWT_SECRET");    }

    @Test
    @DisplayName("配齐必填项时上下文可启动，且配置正确绑定")
    void contextStartsWithAllRequiredVariables() {
        List<String> args = baseArgs();
        args.add("--app.hmac.secret=dummy-secret-for-test");
        args.add("--app.jwt.secret=0123456789abcdef0123456789abcdef");

        try (ConfigurableApplicationContext ctx = nonWebApp().run(args.toArray(String[]::new))) {
            AppProperties p = ctx.getBean(AppProperties.class);
            assertThat(p.hmac().keyId()).isEqualTo("k1");
            assertThat(p.hmac().secret()).isEqualTo("dummy-secret-for-test");
            assertThat(p.hmac().clockSkewSeconds()).isEqualTo(60);
            assertThat(p.rag().port()).isEqualTo(50051);
            assertThat(p.rag().tls().enabled()).isTrue();
            assertThat(p.sse().timeoutMs()).isEqualTo(180_000);
            assertThat(p.sse().heartbeatMs()).isEqualTo(15_000);
            assertThat(p.upload().allowedExt()).isEqualTo("md,txt");
            assertThat(p.upload().maxBytes()).isEqualTo(10_485_760L);
            assertThat(p.rateLimit().chatPerMin()).isEqualTo(20);
        }
    }

    @Test
    @DisplayName("test_config_required_missing: RAG_TLS_ENABLED=false 时启动失败（NFR-03 禁止明文 gRPC）")
    void tlsDisabledIsRejected() {
        List<String> args = allRequiredArgs();
        args.add("--app.rag.tls.enabled=false");

        Throwable failure = catchThrowable(() -> nonWebApp().run(args.toArray(String[]::new)));

        assertThat(failure).as("RAG_TLS_ENABLED=false 时必须启动失败，而不是带着明文 gRPC 跑起来").isNotNull();
        assertThat(allMessages(failure)).as("失败信息必须指向 RAG_TLS_ENABLED").contains("RAG_TLS_ENABLED");
    }

    @Test
    @DisplayName("test_config_required_missing: TLS 开关为 null 视图下的默认值即 true")
    void tlsEnabledByDefault() {
        // 回归护栏：上面的 @AssertTrue 不能把「不传该变量」也拒掉，
        // application.yml 里 RAG_TLS_ENABLED 的默认必须是 true。
        try (ConfigurableApplicationContext ctx = nonWebApp().run(allRequiredArgs().toArray(String[]::new))) {
            assertThat(ctx.getBean(AppProperties.class).rag().tls().enabled()).isTrue();
        }
    }

    /** 把异常链上的所有 message 拼起来，便于断言「变量名出现在失败信息里」。 */
    private static String allMessages(Throwable failure) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t.getMessage() != null) {
                sb.append(t.getMessage()).append(" | ");
            }
        }
        return sb.toString();
    }
}
