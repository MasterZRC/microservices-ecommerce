package com.ecommerce.recommendation.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * DeepFM 排序服务客户端
 * 调用 Python recommendation-rank-service 进行 CTR 预估排序
 * 
 * 功能：
 * - API Key 认证
 * - 特征完整性验证
 * - 优雅降级（排序失败时返回原始候选）
 * - 健康检查与超时控制
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RankClientService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RankClientService.class);

    private final RestTemplate restTemplate;

    @Value("${services.rank.url:http://localhost:8010}")
    private String rankServiceUrl;

    @Value("${services.rank.timeout-ms:5000}")
    private int timeoutMs;

    @Value("${services.rank.api-key:}")
    private String apiKey;

    @Value("${recommendation.rerank.enabled:true}")
    private boolean rerankEnabled;

    @Value("${recommendation.rank.min-feature-ratio:0.5}")
    private double minFeatureRatio;

    private static final String HEADER_API_KEY = "X-API-Key";

    /**
     * 对候选商品进行排序
     * 
     * @return 排序后的商品ID列表，若排序失败则返回原始候选列表
     */
    public List<Long> rank(Long userId, List<Long> candidateIds, 
                           Map<String, Object> userFeatures,
                           Map<String, Map<String, Object>> itemFeatures) {
        
        if (!rerankEnabled) {
            log.debug("重排未启用，直接返回候选列表");
            return candidateIds;
        }

        if (candidateIds == null || candidateIds.isEmpty()) {
            return Collections.emptyList();
        }

        // 验证特征完整性
        FeatureValidationResult validation = validateFeatures(userFeatures, itemFeatures, candidateIds);
        if (!validation.isValid) {
            log.warn("特征验证失败: userId={}, reason={}, candidates={}", 
                    userId, validation.reason, candidateIds.size());
            // 记录告警指标
            recordFeatureAlert(userId, validation.reason);
        }

        try {
            Map<String, Object> request = buildRankRequest(userId, candidateIds, userFeatures, itemFeatures);
            String url = rankServiceUrl + "/rank/simple";
            
            log.debug("调用排序服务: url={}, candidates={}, hasUserFeatures={}, hasItemFeatures={}", 
                    url, candidateIds.size(), 
                    userFeatures != null && !userFeatures.isEmpty(),
                    itemFeatures != null && !itemFeatures.isEmpty());

            ResponseEntity<Map> response = executeRankRequest(url, request);
            
            if (response != null && response.getBody() != null) {
                Map<String, Object> body = response.getBody();
                if (body.containsKey("ranked_items")) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> rankedItems = (List<Map<String, Object>>) body.get("ranked_items");
                    
                    if (rankedItems != null && !rankedItems.isEmpty()) {
                        log.info("排序成功: userId={}, candidates={}, ranked={}", 
                                userId, candidateIds.size(), rankedItems.size());
                        
                        return rankedItems.stream()
                                .map(item -> {
                                    Object id = item.get("item_id");
                                    if (id instanceof Number) {
                                        return ((Number) id).longValue();
                                    }
                                    return null;
                                })
                                .filter(Objects::nonNull)
                                .collect(Collectors.toList());
                    }
                }
            }
            
            log.warn("排序响应异常: userId={}, response={}", userId, response);
            return candidateIds;

        } catch (HttpStatusCodeException e) {
            log.error("排序服务返回错误: userId={}, status={}, body={}", 
                    userId, e.getStatusCode(), e.getResponseBodyAsString());
            recordRankError(userId, "http_error", e.getStatusCode().value());
            return candidateIds;
            
        } catch (RestClientException e) {
            log.error("排序服务调用失败: userId={}, error={}", userId, e.getMessage());
            recordRankError(userId, "connection_error", 0);
            return candidateIds;
            
        } catch (Exception e) {
            log.error("排序服务未知错误: userId={}, error={}", userId, e.getMessage(), e);
            recordRankError(userId, "unknown_error", 0);
            return candidateIds;
        }
    }

    /**
     * 带分数的排序方法
     * 调用 /rank/simple，返回 Map&lt;商品ID, DeepFM分数&gt;
     *
     * @return 排序后的商品ID及其DeepFM分数映射
     */
    public Map<Long, Double> rankWithScores(Long userId, List<Long> candidateIds,
                                            Map<String, Object> userFeatures,
                                            Map<String, Map<String, Object>> itemFeatures) {
        Map<Long, Double> result = new LinkedHashMap<>();

        if (!rerankEnabled) {
            for (Long id : candidateIds) result.put(id, 0.5);
            return result;
        }

        if (candidateIds == null || candidateIds.isEmpty()) {
            return result;
        }

        try {
            Map<String, Object> request = buildRankRequest(userId, candidateIds, userFeatures, itemFeatures);
            String url = rankServiceUrl + "/rank/simple";

            ResponseEntity<Map> response = executeRankRequest(url, request);

            if (response != null && response.getBody() != null) {
                Map<String, Object> body = response.getBody();
                if (body.containsKey("ranked_items")) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> rankedItems = (List<Map<String, Object>>) body.get("ranked_items");

                    for (Map<String, Object> item : rankedItems) {
                        Object id = item.get("item_id");
                        Object score = item.get("score");
                        if (id instanceof Number) {
                            long productId = ((Number) id).longValue();
                            double scoreValue = 0.0;
                            if (score instanceof Number) {
                                scoreValue = ((Number) score).doubleValue();
                            }
                            result.put(productId, scoreValue);
                        }
                    }

                    if (!result.isEmpty()) {
                        log.info("排序成功(含分数): userId={}, ranked={}", userId, result.size());
                        return result;
                    }
                }
            }

            log.warn("排序响应异常，返回默认分数: userId={}", userId);

        } catch (HttpStatusCodeException e) {
            log.error("排序服务返回错误: userId={}, status={}, body={}",
                    userId, e.getStatusCode(), e.getResponseBodyAsString());
            recordRankError(userId, "http_error", e.getStatusCode().value());
        } catch (RestClientException e) {
            log.error("排序服务调用失败: userId={}, error={}", userId, e.getMessage());
            recordRankError(userId, "connection_error", 0);
        } catch (Exception e) {
            log.error("排序服务未知错误: userId={}, error={}", userId, e.getMessage(), e);
            recordRankError(userId, "unknown_error", 0);
        }

        // 降级：所有候选返回默认分数
        for (Long id : candidateIds) result.put(id, 0.5);
        return result;
    }

    /**
     * 验证特征完整性
     */
    private FeatureValidationResult validateFeatures(Map<String, Object> userFeatures, 
                                                     Map<String, Map<String, Object>> itemFeatures,
                                                     List<Long> candidateIds) {
        // 验证用户特征
        if (userFeatures == null || userFeatures.isEmpty()) {
            return new FeatureValidationResult(false, "user_features_empty");
        }
        
        // 检查关键用户特征是否存在
        String[] requiredUserFeatures = {"view_1d", "click_1d", "view_7d"};
        boolean hasUserFeature = false;
        for (String key : requiredUserFeatures) {
            if (userFeatures.containsKey(key) && userFeatures.get(key) != null) {
                hasUserFeature = true;
                break;
            }
        }
        if (!hasUserFeature) {
            return new FeatureValidationResult(false, "user_features_missing_required_keys");
        }

        // 额外检查：last_active_hours 是否在合理范围内（≤ 24h 或 ≤ 720h 但 view_7d > 0）
        Object lastActiveHoursObj = userFeatures.get("last_active_hours");
        if (lastActiveHoursObj != null) {
            int lastActiveHours = getIntValue(userFeatures, "last_active_hours", 0);
            Integer view7d = getIntValue(userFeatures, "view_7d", 0);
            // 如果用户超过 24h 未活跃且 7 天内无行为，则为异常用户（可能是行为数据损坏）
            if (lastActiveHours > 24 && view7d <= 0) {
                return new FeatureValidationResult(false,
                    String.format("last_active_hours_too_large:%d", lastActiveHours));
            }
        }

        // 验证商品特征覆盖率
        if (itemFeatures == null || itemFeatures.isEmpty()) {
            return new FeatureValidationResult(false, "item_features_empty");
        }
        
        int matchedCount = 0;
        for (Long candidateId : candidateIds) {
            String key = String.valueOf(candidateId);
            Map<String, Object> feat = itemFeatures.get(key);
            if (feat != null && !feat.isEmpty()) {
                matchedCount++;
            }
        }
        
        double featureRatio = candidateIds.isEmpty() ? 0 : (double) matchedCount / candidateIds.size();
        if (featureRatio < minFeatureRatio) {
            return new FeatureValidationResult(false, 
                    String.format("item_feature_ratio_too_low:%.2f", featureRatio));
        }

        return new FeatureValidationResult(true, "valid");
    }

    /**
     * 构建排序请求
     */
    private Map<String, Object> buildRankRequest(Long userId, List<Long> candidateIds,
                                                  Map<String, Object> userFeatures,
                                                  Map<String, Map<String, Object>> itemFeatures) {
        Map<String, Object> request = new HashMap<>();
        request.put("user_id", userId);
        request.put("candidates", candidateIds.stream()
                .map(Long::intValue)
                .collect(Collectors.toList()));
        
        // 添加用户特征
        if (userFeatures != null) {
            request.put("view_1d", getIntValue(userFeatures, "view_1d", 0));
            request.put("click_1d", getIntValue(userFeatures, "click_1d", 0));
            request.put("cart_1d", getIntValue(userFeatures, "cart_1d", 0));
            request.put("buy_1d", getIntValue(userFeatures, "buy_1d", 0));
            request.put("view_7d", getIntValue(userFeatures, "view_7d", 0));
            request.put("click_7d", getIntValue(userFeatures, "click_7d", 0));
            request.put("cart_7d", getIntValue(userFeatures, "cart_7d", 0));
            request.put("buy_7d", getIntValue(userFeatures, "buy_7d", 0));
            request.put("last_active_hours", getIntValue(userFeatures, "last_active_hours", 24));
            
            // 偏好特征
            Object preferCategory = userFeatures.get("prefer_category");
            if (preferCategory instanceof List) {
                request.put("prefer_category", preferCategory);
            }
            Object preferBrand = userFeatures.get("prefer_brand");
            if (preferBrand instanceof List) {
                request.put("prefer_brand", preferBrand);
            }
        }

        // 添加商品特征
        Map<String, Map<String, Object>> itemFeatMap = buildItemFeaturesMap(itemFeatures, candidateIds);
        if (itemFeatMap != null && !itemFeatMap.isEmpty()) {
            request.put("item_features", itemFeatMap);
        }

        return request;
    }

    /**
     * 执行排序请求（带 API Key 认证）
     */
    private ResponseEntity<Map> executeRankRequest(String url, Map<String, Object> request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        // 添加 API Key 认证
        if (apiKey != null && !apiKey.isBlank()) {
            headers.set(HEADER_API_KEY, apiKey);
            log.trace("API Key 已配置，将添加到请求头");
        }

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);
        return restTemplate.postForEntity(url, entity, Map.class);
    }

    /**
     * 构建商品特征 Map
     * 
     * 注意：如果无法获取完整特征，会记录告警但不生成假数据
     */
    private Map<String, Map<String, Object>> buildItemFeaturesMap(
            Map<String, Map<String, Object>> itemFeatures, List<Long> candidateIds) {
        Map<String, Map<String, Object>> result = new HashMap<>();
        int missingCount = 0;
        
        for (Long itemId : candidateIds) {
            String key = String.valueOf(itemId);
            Map<String, Object> feat = itemFeatures != null ? itemFeatures.get(key) : null;
            
            if (feat == null || feat.isEmpty()) {
                missingCount++;
                // 不生成假特征，只记录缺失
                continue;
            }
            
            result.put(key, Map.of(
                    "category_id", getIntValue(feat, "category_id", 0),
                    "brand_id", getIntValue(feat, "brand_id", 0),
                    "price_bucket", getIntValue(feat, "price_bucket", 0),
                    "sales_bucket", getIntValue(feat, "sales_bucket", 0),
                    "hot_score", getDoubleValue(feat, "hot_score", 100.0),
                    "price_ratio", getDoubleValue(feat, "price_ratio", 0.0)
            ));
        }
        
        if (missingCount > 0) {
            log.warn("部分商品特征缺失: missing={}, total={}", missingCount, candidateIds.size());
        }
        
        return result;
    }

    /**
     * 记录特征告警
     */
    private void recordFeatureAlert(Long userId, String reason) {
        // 使用 log 告警，生产环境可发送到监控系统
        log.warn("RANK_FEATURE_ALERT: userId={}, reason={}, timestamp={}", 
                userId, reason, System.currentTimeMillis());
    }

    /**
     * 记录排序错误
     */
    private void recordRankError(Long userId, String errorType, int httpStatus) {
        log.warn("RANK_ERROR: userId={}, type={}, httpStatus={}, timestamp={}", 
                userId, errorType, httpStatus, System.currentTimeMillis());
    }

    /**
     * 简化排序（供内部调用）
     */
    public List<Long> rankSimple(Long userId, List<Long> candidateIds) {
        if (!rerankEnabled) {
            return candidateIds;
        }

        if (candidateIds == null || candidateIds.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            Map<String, Object> request = new HashMap<>();
            request.put("user_id", userId);
            request.put("candidates", candidateIds.stream().map(Long::intValue).collect(Collectors.toList()));

            String url = rankServiceUrl + "/rank/simple";
            
            ResponseEntity<Map> response = executeRankRequest(url, request);

            if (response != null && response.getBody() != null) {
                Map<String, Object> body = response.getBody();
                if (body.containsKey("ranked_items")) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> rankedItems = (List<Map<String, Object>>) body.get("ranked_items");
                    
                    return rankedItems.stream()
                            .map(item -> {
                                Object id = item.get("item_id");
                                if (id instanceof Number) {
                                    return ((Number) id).longValue();
                                }
                                return null;
                            })
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());
                }
            }

        } catch (Exception e) {
            log.error("简化排序失败: userId={}, error={}", userId, e.getMessage());
        }

        return candidateIds;
    }

    /**
     * 健康检查（带超时控制）
     */
    public boolean isAvailable() {
        try {
            String url = rankServiceUrl + "/health";
            
            HttpHeaders headers = new HttpHeaders();
            if (apiKey != null && !apiKey.isBlank()) {
                headers.set(HEADER_API_KEY, apiKey);
            }
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, 
                    org.springframework.http.HttpMethod.GET, 
                    entity, 
                    Map.class
            );
            
            return response != null && response.getBody() != null 
                    && "healthy".equals(response.getBody().get("status"));
                    
        } catch (Exception e) {
            log.warn("排序服务健康检查失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 检查重排是否启用
     */
    public boolean isRankEnabled() {
        return rerankEnabled;
    }

    private UserFeatureInfo buildUserFeatures(Map<String, Object> userFeatures) {
        if (userFeatures == null) {
            userFeatures = new HashMap<>();
        }

        return UserFeatureInfo.builder()
                .view1d(getIntValue(userFeatures, "view_1d", 0))
                .click1d(getIntValue(userFeatures, "click_1d", 0))
                .cart1d(getIntValue(userFeatures, "cart_1d", 0))
                .buy1d(getIntValue(userFeatures, "buy_1d", 0))
                .view7d(getIntValue(userFeatures, "view_7d", 0))
                .lastActiveHours(getIntValue(userFeatures, "last_active_hours", 24))
                .preferCategory(buildIntList(userFeatures.get("prefer_category")))
                .preferBrand(buildIntList(userFeatures.get("prefer_brand")))
                .build();
    }

    private Map<String, ItemFeatureInfo> buildItemFeatures(Map<String, Map<String, Object>> itemFeatures, 
                                                         List<Long> candidateIds) {
        Map<String, ItemFeatureInfo> result = new HashMap<>();

        for (Long itemId : candidateIds) {
            String key = String.valueOf(itemId);
            
            Map<String, Object> features = itemFeatures != null ? itemFeatures.get(key) : null;
            if (features == null) {
                features = new HashMap<>();
            }

            result.put(key, ItemFeatureInfo.builder()
                    .categoryId(getIntValue(features, "category_id", 0))
                    .brandId(getIntValue(features, "brand_id", 0))
                    .priceBucket(getIntValue(features, "price_bucket", 0))
                    .salesBucket(getIntValue(features, "sales_bucket", 0))
                    .hotScore(getDoubleValue(features, "hot_score", 100.0))
                    .build());
        }

        return result;
    }

    private int getIntValue(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }

    private double getDoubleValue(Map<String, Object> map, String key, double defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return defaultValue;
    }

    private List<Integer> buildIntList(Object value) {
        if (value == null) {
            return Collections.emptyList();
        }
        if (value instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) value;
            return list.stream()
                    .filter(Objects::nonNull)
                    .map(obj -> {
                        if (obj instanceof Number) {
                            return ((Number) obj).intValue();
                        }
                        return 0;
                    })
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    /**
     * 将驼峰命名的用户特征转换为蛇形命名
     */
    private Map<String, Object> convertToSnakeCaseUserFeatures(Map<String, Object> userFeatures) {
        if (userFeatures == null) {
            userFeatures = new HashMap<>();
        }
        
        Map<String, Object> converted = new HashMap<>();
        converted.put("view_1d", getIntValue(userFeatures, "view_1d", 0));
        converted.put("click_1d", getIntValue(userFeatures, "click_1d", 0));
        converted.put("cart_1d", getIntValue(userFeatures, "cart_1d", 0));
        converted.put("buy_1d", getIntValue(userFeatures, "buy_1d", 0));
        converted.put("view_7d", getIntValue(userFeatures, "view_7d", 0));
        converted.put("last_active_hours", getIntValue(userFeatures, "last_active_hours", 24));
        
        Object preferCategory = userFeatures.get("prefer_category");
        if (preferCategory instanceof List) {
            converted.put("prefer_category", preferCategory);
        } else {
            converted.put("prefer_category", Collections.emptyList());
        }
        
        Object preferBrand = userFeatures.get("prefer_brand");
        if (preferBrand instanceof List) {
            converted.put("prefer_brand", preferBrand);
        } else {
            converted.put("prefer_brand", Collections.emptyList());
        }
        
        return converted;
    }

    /**
     * 将驼峰命名的商品特征转换为蛇形命名
     */
    private Map<String, Map<String, Object>> convertToSnakeCaseItemFeatures(
            Map<String, Map<String, Object>> itemFeatures, List<Long> candidateIds) {
        Map<String, Map<String, Object>> converted = new HashMap<>();
        
        for (Long itemId : candidateIds) {
            String key = String.valueOf(itemId);
            Map<String, Object> originalFeatures = itemFeatures != null ? itemFeatures.get(key) : null;
            
            if (originalFeatures == null) {
                originalFeatures = new HashMap<>();
            }
            
            Map<String, Object> convertedFeatures = new HashMap<>();
            convertedFeatures.put("category_id", getIntValue(originalFeatures, "category_id", 0));
            convertedFeatures.put("brand_id", getIntValue(originalFeatures, "brand_id", 0));
            convertedFeatures.put("price_bucket", getIntValue(originalFeatures, "price_bucket", 0));
            convertedFeatures.put("sales_bucket", getIntValue(originalFeatures, "sales_bucket", 0));
            convertedFeatures.put("hot_score", getDoubleValue(originalFeatures, "hot_score", 100.0));
            
            converted.put(key, convertedFeatures);
        }
        
        return converted;
    }

    // ========== DTO 类 ==========

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RankRequest {
        private Long userId;
        private List<Integer> candidates;
        private UserFeatureInfo userFeatures;
        private Map<String, ItemFeatureInfo> itemFeatures;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RankResponse {
        private Long userId;
        private List<RankedItem> rankedItems;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RankedItem {
        private Long itemId;
        private Double score;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserFeatureInfo {
        private Integer view1d;
        private Integer click1d;
        private Integer cart1d;
        private Integer buy1d;
        private Integer view7d;
        private Integer lastActiveHours;
        private List<Integer> preferCategory;
        private List<Integer> preferBrand;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ItemFeatureInfo {
        private Integer categoryId;
        private Integer brandId;
        private Integer priceBucket;
        private Integer salesBucket;
        private Double hotScore;
    }

    /**
     * 特征验证结果
     */
    @Data
    @AllArgsConstructor
    private static class FeatureValidationResult {
        private boolean isValid;
        private String reason;
    }
}
