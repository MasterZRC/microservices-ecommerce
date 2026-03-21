package com.ecommerce.order.config;

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
 * Swagger / OpenAPI 统一配置
 * 访问地址：
 *   - Swagger UI: http://localhost:8003/swagger-ui.html
 *   - OpenAPI JSON: http://localhost:8003/v3/api-docs
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI orderServiceOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("订单服务 API")
                        .description(
                            "微服务电商系统 — 订单服务模块。\n\n" +
                            "提供购物车管理、订单创建、支付模拟等全链路订单处理能力。\n\n" +
                            "**认证方式**：通过 API Gateway 统一认证，在请求 Header 中添加：\n" +
                            "`Authorization: Bearer <token>`"
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
