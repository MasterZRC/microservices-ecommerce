package com.ecommerce.gateway.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@Tag(name = "网关状态", description = "API网关健康状态和基础信息接口")
public class GatewayStatusController {

    @GetMapping({"/", "/api"})
    @Operation(
        summary = "网关状态",
        description = "返回 API 网关的服务名称、运行状态和当前时间"
    )
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("service", "api-gateway");
        result.put("status", "UP");
        result.put("time", LocalDateTime.now().toString());
        return result;
    }
}
