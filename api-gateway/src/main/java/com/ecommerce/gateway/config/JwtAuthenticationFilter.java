package com.ecommerce.gateway.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JWT 认证过滤器
 * 1. 验证请求中的 JWT Token
 * 2. 提取用户ID并通过内部 Header 传递给下游服务
 * 3. 下游服务应信任此 Header 而非前端传入的 userId 参数
 */
@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    public static final String HEADER_USER_ID = "X-Authenticated-User-Id";
    public static final String HEADER_USER_ROLE = "X-Authenticated-User-Role";

    private static final List<String> EXCLUDED_PATHS = List.of(
            "/api/user/login",
            "/api/user/register",
            "/api/product/list",
            "/api/product/category",
            "/api/recommendation/popular",
            "/api/recommendation/popular/products",
            "/api/recommendation/personal",
            "/api/recommendation/personal/products",
            "/api/recommendation/gray/status",
            "/api/recommendation/gray/check",
            "/api/seckill/products",
            "/api/seckill/products/upcoming",
            "/api/seckill/activity",
            "/health",
            "/actuator"
    );

    @Value("${jwt.secret:mySecretKeyForEcommerceGraduationProject2024}")
    private String jwtSecret;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();

        // 放行无需认证的路径
        if (isExcluded(path)) {
            return chain.filter(exchange);
        }

        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("Missing or invalid Authorization header for path: {}", path);
            return unauthorized(exchange.getResponse());
        }

        String token = authHeader.substring(7);
        try {
            Claims claims = validateAndParseToken(token);
            if (claims == null) {
                return unauthorized(exchange.getResponse());
            }

            Long userId = extractUserId(claims);
            String role = extractRole(claims);
            log.info("JWT validated: userId={}, role={}, path={}", userId, role, path);

            // 将认证后的用户信息通过内部 Header 传递给下游服务
            // 下游服务必须使用此 Header 中的 userId，禁止信任前端传入的 userId 参数
            ServerHttpRequest mutatedRequest = request.mutate()
                    .header(HEADER_USER_ID, String.valueOf(userId))
                    .header(HEADER_USER_ROLE, role != null ? role : "user")
                    .build();

            ServerWebExchange mutatedExchange = exchange.mutate()
                    .request(mutatedRequest)
                    .build();

            return chain.filter(mutatedExchange);

        } catch (Exception e) {
            log.warn("JWT validation failed for path {}: {}", path, e.getMessage());
            return unauthorized(exchange.getResponse());
        }
    }

    private boolean isExcluded(String path) {
        // 只放行登录接口，其他管理接口需要验证 JWT
        if ("/api/admin/auth/login".equals(path)) {
            return true;
        }
        return EXCLUDED_PATHS.stream().anyMatch(path::startsWith);
    }

    private Claims validateAndParseToken(String token) {
        try {
            SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            log.warn("Token parse/verify failed: {}", e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    private Long extractUserId(Claims claims) {
        // 首先尝试 userId
        Object userIdClaim = claims.get("userId");
        if (userIdClaim instanceof Number) {
            return ((Number) userIdClaim).longValue();
        }
        if (userIdClaim instanceof String) {
            try {
                return Long.parseLong((String) userIdClaim);
            } catch (NumberFormatException e) {
                // userId claim 是字符串但不是数字ID，不可用
            }
        }
        // 尝试 adminId（用于管理端）
        Object adminIdClaim = claims.get("adminId");
        if (adminIdClaim instanceof Number) {
            return ((Number) adminIdClaim).longValue();
        }
        if (adminIdClaim instanceof String) {
            try {
                return Long.parseLong((String) adminIdClaim);
            } catch (NumberFormatException e) {
                // adminId claim 是字符串但不是数字ID，不可用
            }
        }
        // 最后 fallback：尝试解析 subject（如果项目使用 username 作为 subject 则不可用）
        Object subject = claims.getSubject();
        if (subject != null) {
            try {
                return Long.parseLong(subject.toString());
            } catch (NumberFormatException e) {
                // subject 不是数字用户ID，无法从中提取 userId
            }
        }
        return null;
    }

    private String extractRole(Claims claims) {
        Object roleClaim = claims.get("role");
        if (roleClaim instanceof String) {
            return (String) roleClaim;
        }
        return null;
    }

    private Mono<Void> unauthorized(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().add(HttpHeaders.CONTENT_TYPE, "application/json");
        String body = "{\"code\":401,\"message\":\"未授权，请先登录\"}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1; // 在 RequestIdFilter 之后执行
    }
}
