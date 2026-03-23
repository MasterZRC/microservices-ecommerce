package com.ecommerce.seckill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ecommerce.seckill.entity.SeckillProduct;
import com.ecommerce.seckill.mapper.SeckillProductMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 秒杀策略服务
 *
 * 核心创新点：
 * 1. 动态库存分配：根据用户等级/购买力分配不同库存池
 * 2. 阶梯抢购：越早抢购库存越充足，越晚抢购库存越紧张
 * 3. 反作弊模块：识别机器下单、同一IP多账号等行为
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillStrategyService {

    private final SeckillProductMapper seckillProductMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RestTemplate restTemplate;

    // ========== Redis Key 常量 ==========
    private static final String SECKILL_STOCK_KEY = "seckill:stock:";
    private static final String USER_STOCK_KEY = "seckill:user:stock:";
    private static final String ANTI_SPAM_KEY = "seckill:anti-spam:";
    private static final String IP_ACCOUNT_KEY = "seckill:ip:accounts:";

    // ========== 配置参数 ==========
    @Value("${services.user.url:http://localhost:8001}")
    private String userServiceUrl;

    @Value("${seckill.strategy.vip-stock-ratio:0.3}")
    private double vipStockRatio;

    @Value("${seckill.strategy.vip-threshold:100}")
    private int vipThreshold;

    @Value("${seckill.strategy.max-requests-per-minute:10}")
    private int maxRequestsPerMinute;

    @Value("${seckill.strategy.ip-max-accounts:3}")
    private int ipMaxAccounts;

    // ========== 统计指标 ==========
    private final AtomicLong vipSeckillCount = new AtomicLong(0);
    private final AtomicLong normalSeckillCount = new AtomicLong(0);
    private final AtomicLong antiSpamRejections = new AtomicLong(0);
    private final AtomicLong ladderSeckillCount = new AtomicLong(0);

    // ========== 策略1：动态库存分配 ==========

    /**
     * 获取用户可用的秒杀库存
     * 根据用户等级分配不同比例的库存
     */
    public int getAvailableStockForUser(Long userId, Long seckillProductId) {
        try {
            String stockKey = SECKILL_STOCK_KEY + seckillProductId;
            String userStockKey = USER_STOCK_KEY + seckillProductId + ":" + userId;

            // 从Redis获取用户分配库存
            String userStockStr = (String) redisTemplate.opsForValue().get(userStockKey);
            if (userStockStr != null) {
                return Integer.parseInt(userStockStr);
            }

            // 首次访问，分配库存
            String stockStr = (String) redisTemplate.opsForValue().get(stockKey);
            if (stockStr == null) {
                return 0;
            }

            int totalStock = Integer.parseInt(stockStr);

            // 判断用户等级
            String userLevel = getUserLevel(userId);
            int userStock;

            if ("vip".equals(userLevel) || "high".equals(userLevel)) {
                // VIP用户获得更多库存（30%）
                userStock = (int) (totalStock * vipStockRatio);
                vipSeckillCount.incrementAndGet();
            } else {
                // 普通用户获得较少库存（剩余的10%）
                userStock = (int) (totalStock * (1 - vipStockRatio) / 10);
                normalSeckillCount.incrementAndGet();
            }

            // 分配库存到用户（1小时过期）
            redisTemplate.opsForValue().set(userStockKey, String.valueOf(userStock), 1, TimeUnit.HOURS);

            log.info("用户库存分配: userId={}, productId={}, level={}, stock={}",
                userId, seckillProductId, userLevel, userStock);

            return userStock;
        } catch (Exception e) {
            log.warn("获取用户库存失败: userId={}, productId={}, error={}",
                userId, seckillProductId, e.getMessage());
            return 0;
        }
    }

    /**
     * 获取用户等级
     * 基于RFM模型判断用户价值
     */
    private String getUserLevel(Long userId) {
        try {
            // 查询用户购买历史（从Redis）
            Long buyCount = (Long) redisTemplate.opsForValue().get("user:buy:count:" + userId);
            Long activeScore = (Long) redisTemplate.opsForValue().get("user:active:score:" + userId);

            // VIP：购买次数>=阈值 或 活跃度得分>=500
            if ((buyCount != null && buyCount >= vipThreshold) ||
                (activeScore != null && activeScore >= 500)) {
                return "vip";
            }

            // 高活跃：活跃度得分>=200
            if (activeScore != null && activeScore >= 200) {
                return "high";
            }

            // 中活跃：活跃度得分>=50
            if (activeScore != null && activeScore >= 50) {
                return "medium";
            }

        } catch (Exception e) {
            log.warn("获取用户等级失败: userId={}, error={}", userId, e.getMessage());
        }

        return "normal";
    }

    // ========== 策略2：阶梯抢购 ==========

    /**
     * 根据抢购时间获取库存系数
     * 活动开始时库存充足，越往后越紧张
     */
    public double getStockMultiplier(Long seckillProductId) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startTime = getSeckillStartTime(seckillProductId);
        LocalDateTime endTime = getSeckillEndTime(seckillProductId);

        if (now.isBefore(startTime)) {
            // 活动未开始
            return 0.0;
        }

        if (now.isAfter(endTime)) {
            // 活动已结束
            return 0.0;
        }

        // 计算活动进行时间百分比
        long totalDuration = java.time.Duration.between(startTime, endTime).toMinutes();
        long elapsed = java.time.Duration.between(startTime, now).toMinutes();

        if (totalDuration <= 0) return 1.0;

        double progress = (double) elapsed / totalDuration;

        // 越早库存越充足，系数越高（1.0 -> 0.5）
        // 活动开始时：1.0（100%库存可用）
        // 活动中期：0.75（75%库存可用）
        // 活动结束时：0.5（50%库存可用）
        double multiplier = 1.0 - progress * 0.5;

        ladderSeckillCount.incrementAndGet();

        log.debug("阶梯库存系数: productId={}, progress={}%, multiplier={}",
            seckillProductId, (int)(progress * 100), multiplier);

        return Math.max(0.5, multiplier); // 最低50%
    }

    /**
     * 获取秒杀活动开始时间
     */
    private LocalDateTime getSeckillStartTime(Long seckillProductId) {
        LambdaQueryWrapper<SeckillProduct> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SeckillProduct::getId, seckillProductId);
        SeckillProduct product = seckillProductMapper.selectOne(wrapper);
        return product != null ? product.getStartTime() : LocalDateTime.now();
    }

    /**
     * 获取秒杀活动结束时间
     */
    private LocalDateTime getSeckillEndTime(Long seckillProductId) {
        LambdaQueryWrapper<SeckillProduct> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SeckillProduct::getId, seckillProductId);
        SeckillProduct product = seckillProductMapper.selectOne(wrapper);
        return product != null ? product.getEndTime() : LocalDateTime.now().plusHours(24);
    }

    // ========== 策略3：反作弊 ==========

    /**
     * 检查用户是否可疑
     */
    public boolean isSuspiciousUser(Long userId, String ip) {
        // 1. 检查用户请求频率
        if (isUserRateLimited(userId)) {
            log.warn("用户请求频率过高: userId={}", userId);
            antiSpamRejections.incrementAndGet();
            return true;
        }

        // 2. 检查同一IP多账号
        if (isIpMultiAccount(ip)) {
            log.warn("同一IP多账号: ip={}", ip);
            antiSpamRejections.incrementAndGet();
            return true;
        }

        return false;
    }

    /**
     * 记录用户请求（用于频率控制）
     */
    public void recordUserRequest(Long userId, String ip) {
        try {
            // 记录用户请求频率
            String rateLimitKey = ANTI_SPAM_KEY + "rate:" + userId;
            Long count = redisTemplate.opsForValue().increment(rateLimitKey);
            if (count != null && count == 1) {
                redisTemplate.expire(rateLimitKey, 60, TimeUnit.SECONDS);
            }

            // 记录IP关联的账号
            String ipAccountKey = IP_ACCOUNT_KEY + ip;
            redisTemplate.opsForSet().add(ipAccountKey, String.valueOf(userId));
            redisTemplate.expire(ipAccountKey, 24, TimeUnit.HOURS);

        } catch (Exception e) {
            log.warn("记录用户请求失败: userId={}, error={}", userId, e.getMessage());
        }
    }

    /**
     * 检查用户请求频率
     */
    private boolean isUserRateLimited(Long userId) {
        String rateLimitKey = ANTI_SPAM_KEY + "rate:" + userId;
        Long count = redisTemplate.opsForValue().increment(rateLimitKey);
        if (count != null && count == 1) {
            redisTemplate.expire(rateLimitKey, 60, TimeUnit.SECONDS);
        }
        return count != null && count > maxRequestsPerMinute;
    }

    /**
     * 检查同一IP多账号
     */
    private boolean isIpMultiAccount(String ip) {
        if (ip == null || ip.isEmpty()) return false;
        String ipAccountKey = IP_ACCOUNT_KEY + ip;
        Long count = redisTemplate.opsForSet().size(ipAccountKey);
        return count != null && count >= ipMaxAccounts;
    }

    /**
     * 获取用户被拒绝次数
     */
    public long getAntiSpamRejections() {
        return antiSpamRejections.get();
    }

    // ========== 监控接口 ==========

    public Map<String, Object> getStrategyMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("vipSeckillCount", vipSeckillCount.get());
        metrics.put("normalSeckillCount", normalSeckillCount.get());
        metrics.put("antiSpamRejections", antiSpamRejections.get());
        metrics.put("ladderSeckillCount", ladderSeckillCount.get());
        return metrics;
    }
}
