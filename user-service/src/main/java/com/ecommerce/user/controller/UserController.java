package com.ecommerce.user.controller;

import com.ecommerce.user.dto.LoginRequest;
import com.ecommerce.user.dto.LoginResponse;
import com.ecommerce.user.dto.RegisterRequest;
import com.ecommerce.user.entity.User;
import com.ecommerce.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
@Tag(name = "用户服务", description = "用户注册、登录、信息管理接口")
public class UserController {

    private final UserService userService;

    @PostMapping("/register")
    @Operation(
        summary = "用户注册",
        description = "新用户注册账号，支持用户名、密码、邮箱、手机号等信息注册"
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "注册成功",
            content = @Content(schema = @Schema(implementation = LoginResponse.class))
        ),
        @ApiResponse(responseCode = "400", description = "参数校验失败或用户名已存在")
    })
    public ResponseEntity<LoginResponse> register(
            @Parameter(description = "注册请求信息", required = true)
            @Valid @RequestBody RegisterRequest request) {
        LoginResponse response = userService.register(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/login")
    @Operation(
        summary = "用户登录",
        description = "用户登录系统，成功返回 JWT Token，用于后续接口的身份认证"
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "登录成功",
            content = @Content(schema = @Schema(implementation = LoginResponse.class))
        ),
        @ApiResponse(responseCode = "401", description = "用户名或密码错误")
    })
    public ResponseEntity<LoginResponse> login(
            @Parameter(description = "登录请求信息", required = true)
            @Valid @RequestBody LoginRequest request) {
        LoginResponse response = userService.login(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    @Operation(
        summary = "获取用户信息",
        description = "根据用户ID查询用户基本信息，密码字段已脱敏"
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "查询成功",
            content = @Content(schema = @Schema(implementation = User.class))
        ),
        @ApiResponse(responseCode = "404", description = "用户不存在")
    })
    public ResponseEntity<User> getUserById(
            @Parameter(description = "用户ID", required = true)
            @PathVariable Long id) {
        User user = userService.getUserById(id);
        if (user != null) {
            user.setPassword("***");
        }
        return ResponseEntity.ok(user);
    }

    @GetMapping("/check/{username}")
    @Operation(
        summary = "检查用户名是否存在",
        description = "用于注册时的用户名唯一性校验"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "查询成功")
    })
    public ResponseEntity<Map<String, Boolean>> checkUsername(
            @Parameter(description = "待检查的用户名", required = true)
            @PathVariable String username) {
        boolean exists = userService.checkUsernameExists(username);
        Map<String, Boolean> result = new HashMap<>();
        result.put("exists", exists);
        return ResponseEntity.ok(result);
    }

    @PutMapping("/update")
    @Operation(
        summary = "更新用户信息",
        description = "更新用户的昵称、头像、手机号等基本信息"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "更新成功"),
        @ApiResponse(responseCode = "400", description = "参数无效")
    })
    public ResponseEntity<Map<String, String>> updateUser(
            @Parameter(description = "用户信息（需包含id）", required = true)
            @RequestBody User user) {
        userService.updateUser(user);
        Map<String, String> result = new HashMap<>();
        result.put("message", "更新成功");
        return ResponseEntity.ok(result);
    }
}