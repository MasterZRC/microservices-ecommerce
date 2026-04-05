package com.ecommerce.admin.filter;

import com.ecommerce.admin.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AdminJwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;

    private static final List<String> EXCLUDED_PATHS = List.of(
            "/api/admin/auth/login",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/swagger-ui.html",
            "/actuator/**"
    );

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        if (isExcluded(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            sendUnauthorized(response, "未授权，请先登录");
            return;
        }

        String token = authHeader.substring(7);
        try {
            if (jwtUtil.isTokenExpired(token)) {
                sendUnauthorized(response, "Token 已过期，请重新登录");
                return;
            }

            Long adminId = jwtUtil.extractAdminId(token);
            if (adminId == null) {
                sendUnauthorized(response, "无效的 Token");
                return;
            }

            String username = jwtUtil.extractUsername(token);
            String role = jwtUtil.extractRole(token);

            request.setAttribute("adminId", adminId);
            request.setAttribute("adminUsername", username);
            request.setAttribute("adminRole", role != null ? role : "admin");

            String roleName = role != null ? role : "admin";
            String authority = roleName.startsWith("ROLE_") ? roleName : "ROLE_" + roleName.toUpperCase().replace('-', '_');
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    username,
                    null,
                    Collections.singletonList(new SimpleGrantedAuthority(authority)));
            SecurityContextHolder.getContext().setAuthentication(authentication);

            try {
                filterChain.doFilter(request, response);
            } finally {
                SecurityContextHolder.clearContext();
            }

        } catch (Exception e) {
            log.warn("Admin JWT validation failed for path {}: {}", path, e.getMessage());
            sendUnauthorized(response, "认证失败");
        }
    }

    private boolean isExcluded(String path) {
        return EXCLUDED_PATHS.stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = Map.of(
                "code", 401,
                "message", message
        );
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
