package com.ncepu.rag.client;

import com.ncepu.rag.config.AppProperties;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLException;
import java.io.File;

/**
 * gRPC 客户端 Channel 配置（03 §3.6、§5.5）。
 *
 * <p>三条硬约束，改这个类之前先读：
 * <ul>
 *   <li><b>必须走 TLS</b>：{@code RAG_TLS_ENABLED} 已在 {@link AppProperties} 里被断言为 true，
 *       因此这里不提供明文分支——想连明文得先让服务起不来。</li>
 *   <li><b>禁止跳过证书校验</b>：不许出现 {@code InsecureTrustManagerFactory}。
 *       一旦跳过，NFR-03「明文 gRPC 被拒」的验收就不成立了。</li>
 *   <li><b>主机名校验必须保留</b>：gRPC 按连接地址校验服务端证书的 SAN。开发证书里
 *       已签入 {@code IP:127.0.0.1} 与 {@code DNS:localhost}；若换成只签域名的证书再用 IP 连接，
 *       必须通过 {@code overrideAuthority} 指明域名，否则握手失败且报错信息不指向真正原因。</li>
 * </ul>
 *
 * <p>Channel 是进程级单例、创建时不建连（首次调用才握手），因此这里提前构造不会拖慢启动；
 * 但 CA 文件不可读会立刻失败——这正是我们要的「配置错就别启动」。
 */
@Configuration
public class GrpcClientConfig {

    @Bean(destroyMethod = "shutdownNow")
    public ManagedChannel ragManagedChannel(AppProperties props) throws SSLException {
        AppProperties.Rag rag = props.rag();

        SslContext sslContext = GrpcSslContexts.forClient()
                .trustManager(new File(rag.tls().caPath()))
                .build();

        NettyChannelBuilder builder = NettyChannelBuilder
                .forAddress(rag.host(), rag.port())
                .sslContext(sslContext);

        String overrideAuthority = rag.tls().overrideAuthority();
        if (overrideAuthority != null && !overrideAuthority.isBlank()) {
            builder.overrideAuthority(overrideAuthority);
        }

        return builder.build();
    }
}
