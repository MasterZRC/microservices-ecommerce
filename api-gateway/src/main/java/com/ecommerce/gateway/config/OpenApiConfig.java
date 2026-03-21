package com.ecommerce.gateway.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger / OpenAPI 统一配置（Gateway 使用 WebFlux 版本）
 * 访问地址：
 *   - Swagger UI: http://localhost:8080/swagger-ui.html
 *   - OpenAPI JSON: http://localhost:8080/v3/api-docs
 *
 * 注意：Gateway 上的 Swagger 展示的是网关聚合的所有下游服务路由信息
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI gatewayOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("API Gateway - 微服务统一入口")
                        .description(
                            "微服务电商系统 — API 网关。\n\n" +
                            "## 网关职责\n\n" +
                            "1. **路由转发**：将请求精准路由到对应的下游微服务\n" +
                            "2. **身份认证**：JWT Token 解析与验证，提取用户身份\n" +
                            "3. **限流防护**：基于 Redis 的令牌桶限流，保护下游服务\n" +
                            "4. **跨域处理**：统一处理前端 CORS 请求\n" +
                            "5. **统一入口**：单一入口，简化客户端对接\n\n" +
                            "## 路由配置\n\n" +
                            "| 路径前缀 | 目标服务 | 端口 |\n" +
                            "|---------|---------|------|\n" +
                            "| /api/user | user-service | 8001 |\n" +
                            "| /api/product | product-service | 8002 |\n" +
                            "| /api/order | order-service | 8003 |\n" +
                            "| /api/recommendation | recommendation-service | 8004 |\n" +
                            "| /api/seckill | seckill-service | 8005 |\n\n" +
                            "## 认证方式\n" +
                            "网关统一验证 JWT Token，无需在下游服务重复认证。"
                        )
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("电商毕设开发组")
                                .email("dev@ecommerce.com"))
                        .license(new License()
                                .name("MIT License")
                                .url("https://opensource.org/licenses/MIT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("输入登录后获得的 JWT Token")));
    }
}
