package com.contentops.server.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * SpringDoc OpenAPI 全局配置（单体架构）。
 *
 * <p>定义 API 文档的元信息（标题、版本、描述、联系人）和服务器列表。
 * 前端项目通过以下端点访问文档：
 * <ul>
 *   <li>Swagger UI: {@code http://localhost:8080/swagger-ui.html}</li>
 *   <li>OpenAPI 3 JSON: {@code http://localhost:8080/v3/api-docs}</li>
 * </ul>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Content Ops Agent Platform API (Monolithic)")
                        .version("1.0.0")
                        .description("内容运营多 Agent 平台 — 单体架构版本。"
                                + "统一入口管理 6 阶段内容运营流水线（选题→内容→配图→发布→分析→优化），"
                                + "支持循环优化、渐进式生成、人机协同和讨论模式。")
                        .contact(new Contact()
                                .name("Content Ops Team")
                                .email("dev@contentops.com"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0.html")))
                .servers(List.of(
                        new Server()
                                .url("http://localhost:8080")
                                .description("本地开发环境"),
                        new Server()
                                .url("https://api.contentops.com")
                                .description("生产环境")
                ));
    }
}
