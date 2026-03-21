package com.ecommerce.seckill.config;

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
 *   - Swagger UI: http://localhost:8005/swagger-ui.html
 *   - OpenAPI JSON: http://localhost:8005/v3/api-docs
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI seckillServiceOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("秒杀服务 API")
                        .description(
                            "微服务电商系统 — 秒杀服务模块。\n\n" +
                            "## 核心技术亮点\n\n" +
                            "- **Redis Lua 原子脚本**：限流 + 幂等 + 库存扣减一体化原子操作\n" +
                            "- **Redis Stream 消息队列**：异步下单解耦，削峰填谷\n" +
                            "- **布隆过滤器**：快速过滤无效请求，防止缓存穿透\n" +
                            "- **Sentinel 限流熔断**：高并发保护，服务降级兜底\n\n" +
                            "## 认证方式\n" +
                            "通过 API Gateway 统一认证，在请求 Header 中添加：\n" +
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
