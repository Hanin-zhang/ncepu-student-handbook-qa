package com.ncepu.rag.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动后打印一条脱敏的配置摘要，证明「配置项脚手架已加载并通过校验」。
 *
 * <p>只打印非敏感字段；{@code HMAC_SECRET}、{@code JWT_SECRET} 等 Secret 一律不打（NFR-12、C-07）。
 * 这样 P01 的自测可以凭一行日志确认配置生效，而不需要先起任何外部依赖。
 */
@Component
public class ConfigSummaryLogger implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ConfigSummaryLogger.class);

    private final AppProperties props;

    public ConfigSummaryLogger(AppProperties props) {
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("配置校验通过: rag={}:{}, tlsEnabled={}, tlsCaPath={}, hmacKeyId={}, "
                        + "jwtExpireSeconds={}, sseTimeoutMs={}, sseHeartbeatMs={}, "
                        + "uploadMaxBytes={}, uploadAllowedExt={}, ratelimitEnabled={}",
                props.rag().host(), props.rag().port(), props.rag().tls().enabled(),
                props.rag().tls().caPath(), props.hmac().keyId(),
                props.jwt().expireSeconds(), props.sse().timeoutMs(), props.sse().heartbeatMs(),
                props.upload().maxBytes(), props.upload().allowedExt(), props.rateLimit().enabled());
    }
}
