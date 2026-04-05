package com.ecommerce.admin.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI adminServiceOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Admin Service API")
                        .description("商家管理端 API - 商品、订单、秒杀管理")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("E-commerce Team")
                                .email("admin@example.com")));
    }
}
