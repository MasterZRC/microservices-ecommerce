package com.ecommerce.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ecommerce.admin.dto.admin.*;
import com.ecommerce.admin.entity.AdminUser;
import com.ecommerce.admin.mapper.AdminUserMapper;
import com.ecommerce.admin.service.AdminAuthService;
import com.ecommerce.admin.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class AdminAuthServiceImpl implements AdminAuthService {

    private final AdminUserMapper adminUserMapper;
    private final JwtUtil jwtUtil;
    private final RedisTemplate<String, Object> redisTemplate;
    private final PasswordEncoder passwordEncoder;

    @Value("${jwt.expiration}")
    private Long tokenExpiration;

    private static final String TOKEN_PREFIX = "admin:token:";
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public LoginResponse login(LoginRequest request) {
        LambdaQueryWrapper<AdminUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AdminUser::getUsername, request.getUsername());
        AdminUser adminUser = adminUserMapper.selectOne(wrapper);

        if (adminUser == null) {
            throw new RuntimeException("用户名或密码错误");
        }

        if (adminUser.getStatus() == 0) {
            throw new RuntimeException("账号已被禁用");
        }

        boolean matches;
        String stored = adminUser.getPassword();
        if (stored != null && stored.startsWith("$2")) {
            matches = passwordEncoder.matches(request.getPassword(), stored);
        } else {
            matches = request.getPassword().equals(stored);
        }
        if (!matches) {
            throw new RuntimeException("用户名或密码错误");
        }

        String role = adminUser.getRole() != null ? adminUser.getRole() : "admin";

        String token = jwtUtil.generateToken(
                adminUser.getId(),
                adminUser.getUsername(),
                role
        );

        redisTemplate.opsForValue().set(
                TOKEN_PREFIX + adminUser.getId(),
                token,
                tokenExpiration,
                TimeUnit.MILLISECONDS
        );

        AdminUser update = new AdminUser();
        update.setId(adminUser.getId());
        update.setLastLoginTime(LocalDateTime.now());
        update.setLastLoginIp(getRealIp());
        adminUserMapper.updateById(update);

        return new LoginResponse(
                token,
                adminUser.getId(),
                adminUser.getUsername(),
                adminUser.getNickname(),
                role,
                adminUser.getAvatar(),
                "[]"
        );
    }

    @Override
    public void logout(String token) {
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        Long adminId = jwtUtil.extractAdminId(token);
        if (adminId != null) {
            redisTemplate.delete(TOKEN_PREFIX + adminId);
        }
    }

    @Override
    public AdminUserDTO getAdminInfo(Long adminId) {
        AdminUser adminUser = adminUserMapper.selectById(adminId);
        if (adminUser == null) {
            throw new RuntimeException("管理员不存在");
        }

        AdminUserDTO dto = new AdminUserDTO();
        dto.setId(adminUser.getId());
        dto.setUsername(adminUser.getUsername());
        dto.setNickname(adminUser.getNickname());
        dto.setEmail(adminUser.getEmail());
        dto.setPhone(adminUser.getPhone());
        dto.setAvatar(adminUser.getAvatar());
        dto.setRoleName(adminUser.getRole() != null ? adminUser.getRole() : "管理员");
        dto.setPermissions("[]");
        dto.setLastLoginTime(adminUser.getLastLoginTime() != null ?
                adminUser.getLastLoginTime().format(FORMATTER) : null);
        dto.setLastLoginIp(adminUser.getLastLoginIp());

        return dto;
    }

    @Override
    public void updatePassword(Long adminId, PasswordChangeRequest request) {
        AdminUser adminUser = adminUserMapper.selectById(adminId);
        if (adminUser == null) {
            throw new RuntimeException("管理员不存在");
        }

        if (!passwordEncoder.matches(request.getOldPassword(), adminUser.getPassword())) {
            throw new RuntimeException("旧密码错误");
        }

        AdminUser update = new AdminUser();
        update.setId(adminId);
        update.setPassword(passwordEncoder.encode(request.getNewPassword()));
        adminUserMapper.updateById(update);
    }

    private String getRealIp() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) return "127.0.0.1";
        HttpServletRequest request = attrs.getRequest();
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
