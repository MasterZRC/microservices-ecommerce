package com.ecommerce.product.config;

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
 *   - Swagger UI: http://localhost:8002/swagger-ui.html
 *   - OpenAPI JSON: http://localhost:8002/v3/api-docs
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI productServiceOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("商品服务 API")
                        .description(
                            "微服务电商系统 — 商品服务模块。\n\n" +
                            "提供商品浏览、搜索、分类管理、库存扣减等接口。\n\n" +
                            "**认证方式**：部分管理接口需通过 API Gateway 统一认证，\n" +
                            "请在请求 Header 中添加：`Authorization: Bearer <token>`"
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
