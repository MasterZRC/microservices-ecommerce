package com.ecommerce.seckill.controller;

import com.ecommerce.seckill.dto.SeckillDemoJobSnapshot;
import com.ecommerce.seckill.dto.SeckillDemoRequest;
import com.ecommerce.seckill.dto.SeckillDemoResetResult;
import com.ecommerce.seckill.entity.SeckillProduct;
import com.ecommerce.seckill.service.SeckillDemoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/seckill/demo")
@RequiredArgsConstructor
@Tag(name = "Seckill Demo", description = "Admin-only seckill load-test demo APIs")
public class SeckillDemoController {

    private final SeckillDemoService seckillDemoService;

    @GetMapping("/products")
    @Operation(summary = "List active seckill products for demo")
    public ResponseEntity<Map<String, Object>> products(
            @RequestHeader(value = "X-Admin-Id", required = false) Long adminId) {
        ResponseEntity<Map<String, Object>> denied = requireAdmin(adminId);
        if (denied != null) {
            return denied;
        }
        List<SeckillProduct> products = seckillDemoService.getDemoProducts();
        return ok(products);
    }

    @PostMapping("/reset")
    @Operation(summary = "Reset demo stock and clean demo data")
    public ResponseEntity<Map<String, Object>> reset(
            @RequestHeader(value = "X-Admin-Id", required = false) Long adminId,
            @RequestBody SeckillDemoRequest request) {
        ResponseEntity<Map<String, Object>> denied = requireAdmin(adminId);
        if (denied != null) {
            return denied;
        }
        try {
            SeckillDemoResetResult result = seckillDemoService.resetDemo(request);
            return ok("Demo state reset", result);
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (IllegalStateException exception) {
            return error(HttpStatus.CONFLICT, exception.getMessage());
        }
    }

    @PostMapping("/jobs")
    @Operation(summary = "Start a seckill load-test demo job")
    public ResponseEntity<Map<String, Object>> startJob(
            @RequestHeader(value = "X-Admin-Id", required = false) Long adminId,
            @RequestBody SeckillDemoRequest request) {
        ResponseEntity<Map<String, Object>> denied = requireAdmin(adminId);
        if (denied != null) {
            return denied;
        }
        try {
            SeckillDemoJobSnapshot result = seckillDemoService.startJob(request);
            return ok("Demo job started", result);
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (IllegalStateException exception) {
            return error(HttpStatus.CONFLICT, exception.getMessage());
        }
    }

    @GetMapping("/jobs/{jobId}")
    @Operation(summary = "Get a seckill load-test demo job")
    public ResponseEntity<Map<String, Object>> getJob(
            @RequestHeader(value = "X-Admin-Id", required = false) Long adminId,
            @PathVariable String jobId) {
        ResponseEntity<Map<String, Object>> denied = requireAdmin(adminId);
        if (denied != null) {
            return denied;
        }
        try {
            return ok(seckillDemoService.getJob(jobId));
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }

    @PostMapping("/jobs/{jobId}/cancel")
    @Operation(summary = "Cancel a seckill load-test demo job")
    public ResponseEntity<Map<String, Object>> cancelJob(
            @RequestHeader(value = "X-Admin-Id", required = false) Long adminId,
            @PathVariable String jobId) {
        ResponseEntity<Map<String, Object>> denied = requireAdmin(adminId);
        if (denied != null) {
            return denied;
        }
        try {
            return ok("Demo job cancel requested", seckillDemoService.cancelJob(jobId));
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }

    private ResponseEntity<Map<String, Object>> requireAdmin(Long adminId) {
        if (adminId != null) {
            return null;
        }
        return error(HttpStatus.FORBIDDEN, "Admin token is required");
    }

    private ResponseEntity<Map<String, Object>> ok(Object data) {
        return ok("success", data);
    }

    private ResponseEntity<Map<String, Object>> ok(String message, Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 200);
        body.put("message", message);
        body.put("data", data);
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", status.value());
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
