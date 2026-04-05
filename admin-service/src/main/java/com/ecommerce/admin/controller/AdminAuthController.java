package com.ecommerce.admin.controller;

import com.ecommerce.admin.dto.admin.*;
import com.ecommerce.admin.service.AdminAuthService;
import com.ecommerce.admin.util.JwtUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/auth")
@RequiredArgsConstructor
@Tag(name = "认证管理", description = "管理员登录、登出、信息管理")
public class AdminAuthController {

    private final AdminAuthService adminAuthService;
    private final JwtUtil jwtUtil;

    @PostMapping("/login")
    @Operation(summary = "管理员登录", description = "用户名密码登录，返回JWT Token")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "登录成功"),
            @ApiResponse(responseCode = "401", description = "认证失败")
    })
    public com.ecommerce.admin.dto.common.ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = adminAuthService.login(request);
        return com.ecommerce.admin.dto.common.ApiResponse.success("登录成功", response);
    }

    @PostMapping("/logout")
    @Operation(summary = "退出登录")
    public com.ecommerce.admin.dto.common.ApiResponse<Void> logout(@RequestHeader(value = "Authorization", required = false) String token) {
        adminAuthService.logout(token);
        return com.ecommerce.admin.dto.common.ApiResponse.success("退出成功");
    }

    @GetMapping("/info")
    @Operation(summary = "获取当前管理员信息")
    public com.ecommerce.admin.dto.common.ApiResponse<AdminUserDTO> getAdminInfo(
            @Parameter(description = "管理员ID") @RequestHeader(value = "X-Admin-Id", required = false) Long adminId) {
        if (adminId == null) {
            return com.ecommerce.admin.dto.common.ApiResponse.error(401, "未授权访问");
        }
        AdminUserDTO info = adminAuthService.getAdminInfo(adminId);
        return com.ecommerce.admin.dto.common.ApiResponse.success(info);
    }

    @PutMapping("/password")
    @Operation(summary = "修改密码")
    public com.ecommerce.admin.dto.common.ApiResponse<Void> updatePassword(
            @Parameter(description = "管理员ID") @RequestHeader(value = "X-Admin-Id", required = false) Long adminId,
            @Valid @RequestBody PasswordChangeRequest request) {
        if (adminId == null) {
            return com.ecommerce.admin.dto.common.ApiResponse.error(401, "未授权访问");
        }
        adminAuthService.updatePassword(adminId, request);
        return com.ecommerce.admin.dto.common.ApiResponse.success("密码修改成功");
    }
}
