package com.ecommerce.recommendation.service;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * A/B Testing 实验管理服务
 *
 * 支持：
 * - 创建/管理多个实验
 * - 流量分配（基于用户 ID 一致性哈希）
 * - 实验变体分配
 * - 与灰度发布服务集成
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExperimentService {

    private final RedisTemplate<String, Object> redisTemplate;

    /** 实验元数据 Redis 键前缀 */
    private static final String EXP_KEY = "ab:experiment:";
    /** 用户实验分配 Redis 键前缀 */
    private static final String ASSIGN_KEY = "ab:assign:";
    /** 实验列表键 */
    private static final String EXP_LIST_KEY = "ab:experiments";

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ========== 实验管理 ==========

    /**
     * 创建新实验
     */
    public Map<String, Object> createExperiment(String name, List<String> variants,
                                                int trafficPercent, String description) {
        if (trafficPercent < 1 || trafficPercent > 100) {
            throw new IllegalArgumentException("流量比例必须在 1-100 之间");
        }
        if (variants == null || variants.size() < 2) {
            throw new IllegalArgumentException("实验至少需要 2 个变体");
        }

        String experimentId = UUID.randomUUID().toString().substring(0, 8);
        String key = EXP_KEY + experimentId;

        Map<String, Object> experiment = new LinkedHashMap<>();
        experiment.put("id", experimentId);
        experiment.put("name", name);
        experiment.put("variants", variants);
        experiment.put("trafficPercent", trafficPercent);
        experiment.put("description", description != null ? description : "");
        experiment.put("status", "active");
        experiment.put("createdAt", LocalDateTime.now().format(FMT));
        experiment.put("startTime", LocalDateTime.now().format(FMT));
        experiment.put("endTime", "");
        experiment.put("userCount", 0);

        redisTemplate.opsForValue().set(key, experiment);
        redisTemplate.opsForSet().add(EXP_LIST_KEY, experimentId);

        log.info("创建 A/B 实验: id={}, name={}, traffic={}%, variants={}",
                experimentId, name, trafficPercent, variants);

        return experiment;
    }

    /**
     * 获取实验详情
     */
    public Map<String, Object> getExperiment(String experimentId) {
        String key = EXP_KEY + experimentId;
        @SuppressWarnings("unchecked")
        Map<String, Object> exp = (Map<String, Object>) redisTemplate.opsForValue().get(key);
        return exp;
    }

    /**
     * 获取所有实验
     */
    public List<Map<String, Object>> listExperiments() {
        Set<Object> ids = redisTemplate.opsForSet().members(EXP_LIST_KEY);
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> experiments = new ArrayList<>();
        for (Object id : ids) {
            Map<String, Object> exp = getExperiment(id.toString());
            if (exp != null) {
                experiments.add(exp);
            }
        }
        return experiments;
    }

    /**
     * 结束实验
     */
    public void endExperiment(String experimentId) {
        String key = EXP_KEY + experimentId;
        @SuppressWarnings("unchecked")
        Map<String, Object> exp = (Map<String, Object>) redisTemplate.opsForValue().get(key);
        if (exp == null) {
            throw new IllegalArgumentException("实验不存在: " + experimentId);
        }

        exp.put("status", "ended");
        exp.put("endTime", LocalDateTime.now().format(FMT));
        redisTemplate.opsForValue().set(key, exp);
        log.info("结束 A/B 实验: id={}", experimentId);
    }

    /**
     * 删除实验
     */
    public void deleteExperiment(String experimentId) {
        String key = EXP_KEY + experimentId;
        redisTemplate.delete(key);
        redisTemplate.opsForSet().remove(EXP_LIST_KEY, experimentId);
        log.info("删除 A/B 实验: id={}", experimentId);
    }

    // ========== 流量分配 ==========

    /**
     * 获取用户在某个实验中的变体
     * 使用一致性哈希保证同一用户始终分配到同一变体
     */
    public String getVariant(String experimentId, Long userId) {
        String assignKey = ASSIGN_KEY + experimentId + ":" + userId;
        String cached = (String) redisTemplate.opsForValue().get(assignKey);
        if (cached != null) {
            return cached;
        }

        Map<String, Object> exp = getExperiment(experimentId);
        if (exp == null) {
            return null;
        }

        if (!"active".equals(exp.get("status"))) {
            @SuppressWarnings("unchecked")
            List<String> variants = (List<String>) exp.get("variants");
            return variants != null && !variants.isEmpty() ? variants.get(0) : null;
        }

        Integer traffic = (Integer) exp.get("trafficPercent");
        @SuppressWarnings("unchecked")
        List<String> variants = (List<String>) exp.get("variants");
        if (variants == null || variants.isEmpty()) {
            return null;
        }

        // 一致性哈希：hash(userId) % 100 < trafficPercent
        int bucket = Math.abs(Objects.hash(userId)) % 100;
        String variant;
        if (bucket < traffic) {
            int variantIndex = Math.abs(Objects.hash(userId + experimentId)) % variants.size();
            variant = variants.get(variantIndex);
        } else {
            variant = variants.get(0); // 对照组
        }

        // 缓存分配结果（7天）
        redisTemplate.opsForValue().set(assignKey, variant, 7, TimeUnit.DAYS);
        incrementExperimentUserCount(experimentId);

        log.debug("A/B 分配: exp={}, userId={}, bucket={}, variant={}",
                experimentId, userId, bucket, variant);
        return variant;
    }

    /**
     * 获取用户在所有活跃实验中的变体
     */
    public Map<String, String> getAllVariants(Long userId) {
        List<Map<String, Object>> experiments = listExperiments();
        Map<String, String> result = new LinkedHashMap<>();

        for (Map<String, Object> exp : experiments) {
            if (!"active".equals(exp.get("status"))) continue;
            String id = exp.get("id").toString();
            String variant = getVariant(id, userId);
            if (variant != null) {
                result.put(id, variant);
            }
        }

        return result;
    }

    // ========== 实验统计 ==========

    /**
     * 获取实验各变体的参与人数
     */
    public Map<String, Object> getVariantStats(String experimentId) {
        Map<String, Object> exp = getExperiment(experimentId);
        if (exp == null) {
            return Collections.emptyMap();
        }

        @SuppressWarnings("unchecked")
        List<String> variants = (List<String>) exp.get("variants");
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("experimentId", experimentId);
        stats.put("experimentName", exp.get("name"));
        stats.put("status", exp.get("status"));

        Map<String, Long> variantCounts = new LinkedHashMap<>();
        for (String v : variants) {
            variantCounts.put(v, 0L);
        }

        // 从 Redis 中统计分配记录
        String pattern = ASSIGN_KEY + experimentId + ":*";
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null) {
            for (String assignKey : keys) {
                String variant = (String) redisTemplate.opsForValue().get(assignKey);
                if (variant != null) {
                    variantCounts.merge(variant, 1L, Long::sum);
                }
            }
        }

        stats.put("variantCounts", variantCounts);
        stats.put("totalUsers", variantCounts.values().stream().mapToLong(Long::longValue).sum());
        return stats;
    }

    // ========== 内部方法 ==========

    private void incrementExperimentUserCount(String experimentId) {
        String key = EXP_KEY + experimentId;
        redisTemplate.opsForValue().increment(key + ":usercount");
    }

    // ========== 推荐系统集成 ==========

    /**
     * 根据实验变体选择推荐策略
     * 可与 GrayReleaseService 协同使用
     */
    public RecommendationStrategy getRecommendationStrategy(Long userId) {
        // 查找默认推荐实验
        Map<String, Object> exp = getExperiment("default-rec");
        if (exp == null || !"active".equals(exp.get("status"))) {
            return RecommendationStrategy.DEFAULT;
        }

        String variant = getVariant("default-rec", userId);
        if (variant == null) {
            return RecommendationStrategy.DEFAULT;
        }

        return switch (variant) {
            case "itemcf" -> RecommendationStrategy.ITEM_CF;
            case "deepfm" -> RecommendationStrategy.DEEP_FM;
            case "hybrid" -> RecommendationStrategy.HYBRID;
            default -> RecommendationStrategy.DEFAULT;
        };
    }

    public enum RecommendationStrategy {
        DEFAULT,   // 使用当前灰度策略
        ITEM_CF,   // 强制使用 ItemCF
        DEEP_FM,   // 强制使用 DeepFM
        HYBRID     // 混合推荐
    }
}
