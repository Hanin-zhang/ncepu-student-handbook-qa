package com.ncepu.rag.config;

import com.ncepu.rag.RagApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    /**
     * 测试用 CA 证书（{@code src/test/resources/tls/test-ca.crt}）。
     *
     * <p>P02 起只校验「文件存在」不够了：{@code GrpcClientConfig} 会真的把它装进
     * trustManager，内容不是合法证书时启动就会失败。所以测试夹具必须是**真证书**，
     * 不能用随便几个字节凑数。夹具是公开证书（不含私钥），放 resources 里没有泄密风险；
     * 它由 {@code scripts/gen-dev-certs.sh} 生成，到期（2027-11）重新生成一份即可。
     */
    private static Path caFile;

    @BeforeAll
    static void createCaFile() throws IOException {
        caFile = Files.createTempFile("rag-ca", ".crt");
        try (var in = AppPropertiesStartupTest.class.getResourceAsStream("/tls/test-ca.crt")) {
            assertThat(in).as("缺少测试证书夹具 src/test/resources/tls/test-ca.crt").isNotNull();
            Files.copy(in, caFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @AfterAll
    static void deleteCaFile() throws IOException {
        if (caFile != null) {
            Files.deleteIfExists(caFile);
        }
    }

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
        args.add("--app.rag.tls.ca-path=" + caFile);
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
        args.add("--app.rag.tls.ca-path=" + caFile);
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
        assertThat(allMessages(failure))
                .as(() -> "实际失败信息：" + allMessages(failure))
                .contains("HMAC_SECRET");
    }

    @Test
    @DisplayName("test_config_required_missing: 全部必填项同时缺失时，一条失败信息里报出全部变量名")
    void allRequiredVariablesMissingAreAllReported() {
        // 为什么一次删光，而不是逐个删：
        // Spring 的 ConfigurationPropertiesBinder 会把**所有**约束违规收集进同一个
        // BindValidationException，一次启动就能拿到全部字段的错误（实测同时缺 3 个变量时，
        // 失败信息里 3 条 Reason 并列）。逐个删 = 启动 6 次 Spring 上下文，只为验证同一件事。
        //
        // 这条断言比"逐个删"更强：要求 6 个变量名**同时**出现在同一条失败信息里。
        // 哪天 Spring 改成"遇到第一个错误就中止"，这条用例会立刻变红。
        assumeTrue(
                REQUIRED_ENV_TO_PROPERTY.keySet().stream().allMatch(name -> System.getenv(name) == null),
                "本机设置了同名环境变量，无法构造全部缺失的场景");

        // 只给端口，其余必填项一律不传
        List<String> args = new ArrayList<>();
        args.add("--server.port=0");

        Throwable failure = catchThrowable(() -> nonWebApp().run(args.toArray(String[]::new)));

        assertThat(failure).as("全部必填项缺失时必须启动失败").isNotNull();
        String messages = allMessages(failure);
        for (String envName : REQUIRED_ENV_TO_PROPERTY.keySet()) {
            assertThat(messages)
                    .as("失败信息必须包含 %s；实际：%s", envName, messages)
                    .contains(envName);
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
    @DisplayName("test_config_required_missing: CA 文件不存在时启动失败（P02 补洞）")
    void caFileMissingFailsFast() {
        List<String> args = allRequiredArgs().stream()
                .filter(a -> !a.startsWith("--app.rag.tls.ca-path="))
                .collect(Collectors.toList());
        args.add("--app.rag.tls.ca-path=/nonexistent/definitely-not-here.pem");

        Throwable failure = catchThrowable(() -> nonWebApp().run(args.toArray(String[]::new)));

        assertThat(failure).as("CA 文件不存在时必须启动失败，而不是等到握手时才报 SSLHandshakeException")
                .isNotNull();
        assertThat(allMessages(failure)).as("失败信息必须指向 RAG_TLS_CA_PATH").contains("RAG_TLS_CA_PATH");
    }

    @Test
    @DisplayName("配齐必填项时上下文可启动，且配置正确绑定（含 TLS 默认开启）")
    void contextStartsWithAllRequiredVariables() {
        // 与上面的失败路径互补：既要「错的时候炸」，也要证明「对的时候不误伤」。
        // 特别注意 tls().enabled() 必须为 true —— 若 @AssertTrue 误判，
        // 不传 RAG_TLS_ENABLED 的正常配置就会被拒，这条用例负责守住。
        try (ConfigurableApplicationContext ctx =
                     nonWebApp().run(allRequiredArgs().toArray(String[]::new))) {
            AppProperties p = ctx.getBean(AppProperties.class);
            assertThat(p.hmac().keyId()).isEqualTo("k1");
            assertThat(p.hmac().secret()).isEqualTo("dummy-secret-for-test");
            assertThat(p.hmac().clockSkewSeconds()).isEqualTo(60);
            assertThat(p.rag().host()).isEqualTo("127.0.0.1");
            assertThat(p.rag().port()).isEqualTo(50051);
            assertThat(p.rag().tls().enabled()).isTrue();
            assertThat(p.sse().timeoutMs()).isEqualTo(180_000);
            assertThat(p.sse().heartbeatMs()).isEqualTo(15_000);
            assertThat(p.upload().allowedExt()).isEqualTo("md,txt");
            assertThat(p.upload().maxBytes()).isEqualTo(10_485_760L);
            assertThat(p.rateLimit().chatPerMin()).isEqualTo(20);
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
