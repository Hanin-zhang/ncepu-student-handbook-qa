package com.ncepu.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Java 业务服务入口（P01：工程骨架与契约冻结）。
 *
 * <p>P01 只做三件事：① proto 契约由 Maven 生成；② 配置项脚手架；③ 启动即校验——
 * 必填环境变量缺失时进程直接退出并报出变量名（不做静默降级，见 03 §9）。
 *
 * <p>业务能力（认证、会话、SSE、上传）从 P05/P08/P09 开始逐步接入。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class RagApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagApplication.class, args);
    }
}
