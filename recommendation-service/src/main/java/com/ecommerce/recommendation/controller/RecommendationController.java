package com.ecommerce.recommendation.controller;

import com.ecommerce.recommendation.service.ExposureService;
import com.ecommerce.recommendation.service.ExperimentService;
import com.ecommerce.recommendation.service.GrayReleaseService;
import com.ecommerce.recommendation.service.RecommendationService;
import com.ecommerce.recommendation.service.UserProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/recommendation")
@RequiredArgsConstructor
@Tag(name = "推荐服务", description = "个性化推荐、热门推荐、灰度发布、A/B实验、用户画像接口")
public class RecommendationController {

    private final RecommendationService recommendationService;
    private final GrayReleaseService grayReleaseService;
    private final ExperimentService experimentService;
    private final UserProfileService userProfileService;
    private final ExposureService exposureService;

    /**
     * 内部 Header：从网关 JwtAuthenticationFilter 传递的已认证用户ID
     * 下游服务必须信任此 Header 而非前端传入的 userId 参数
     */
    private static final String HEADER_AUTH_USER_ID = "X-Authenticated-User-Id";

    @GetMapping("/personal")
    @Operation(
        summary = "获取个性化推荐商品ID",
        description = "基于用户历史行为的多路召回 + DeepFM/ItemCF 排序，返回推荐商品ID列表"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "查询成功"),
        @ApiResponse(responseCode = "401", description = "用户未登录")
    })
    public ResponseEntity<Map<String, Object>> getPersonalizedRecommendations(
            @Parameter(description = "网关认证用户ID（内部使用）", hidden = true)
            @RequestHeader(value = HEADER_AUTH_USER_ID, required = false) Long authUserId,
            @Parameter(description = "用户ID")
            @RequestParam Long userId,
            @Parameter(description = "推荐数量上限", example = "10")
            @RequestParam(defaultValue = "10") Integer limit) {
        Long verifiedUserId = (authUserId != null) ? authUserId : userId;
        List<Long> recommendations = recommendationService.getPersonalizedRecommendations(verifiedUserId, limit);
        Map<String, Object> result = new HashMap<>();
        result.put("userId", verifiedUserId);
        result.put("recommendations", recommendations);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/personal/products")
    @Operation(
        summary = "获取个性化推荐商品详情",
        description = "返回完整商品信息的个性化推荐列表，包含商品名称、价格、图片和推荐理由"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "查询成功"),
        @ApiResponse(responseCode = "401", description = "用户未登录")
    })
    public ResponseEntity<Map<String, Object>> getPersonalizedRecommendationProducts(
            @Parameter(description = "网关认证用户ID（内部使用）", hidden = true)
            @RequestHeader(value = HEADER_AUTH_USER_ID, required = false) Long authUserId,
            @Parameter(description = "用户ID")
            @RequestParam Long userId,
            @Parameter(description = "推荐数量上限", example = "10")
            @RequestParam(defaultValue = "10") Integer limit) {
        Long verifiedUserId = (authUserId != null) ? authUserId : userId;
        List<Map<String, Object>> products = recommendationService.getPersonalizedProductDetails(verifiedUserId, limit);
        Map<String, Object> result = new HashMap<>();
        result.put("userId", verifiedUserId);
        result.put("products", products);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/popular")
    @Operation(
        summary = "获取热门商品ID",
        description = "基于全局用户行为热度统计的热门商品推荐，无需登录"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "查询成功")
    })
    public ResponseEntity<Map<String, Object>> getPopularItems(
            @Parameter(description = "推荐数量上限", example = "10")
            @RequestParam(defaultValue = "10") Integer limit) {
        List<Long> popularItems = recommendationService.getPopularItems(limit);
        Map<String, Object> result = new HashMap<>();
        result.put("popularItems", popularItems);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/popular/products")
    @Operation(
        summary = "获取热门商品详情",
        description = "返回热门商品的完整信息列表，适合未登录用户的默认推荐"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "查询成功")
    })
    public ResponseEntity<Map<String, Object>> getPopularProducts(
            @Parameter(description = "推荐数量上限", example = "10")
            @RequestParam(defaultValue = "10") Integer limit) {
        List<Map<String, Object>> products = recommendationService.getPopularProductDetails(limit);
        Map<String, Object> result = new HashMap<>();
        result.put("products", products);
        return ResponseEntity.ok(result);
    }

    @Data
    @Schema(description = "用户行为记录请求")
    public static class BehaviorRequest {
        @Schema(description = "用户ID", example = "1")
        public Long userId;
        @Schema(description = "商品ID", example = "100")
        public Long productId;
        @Schema(description = "行为类型：view/click/cart/favorite/buy", example = "view")
        public String behaviorType;
    }

    @PostMapping("/behavior")
    @Operation(
        summary = "记录用户行为",
        description = "记录用户对商品的浏览、点击、加购、收藏、购买等行为，用于推荐系统学习和画像更新"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "记录成功"),
        @ApiResponse(responseCode = "400", description = "参数无效"),
        @ApiResponse(responseCode = "401", description = "用户未登录")
    })
    public ResponseEntity<Map<String, Object>> recordBehavior(
            @Parameter(description = "网关认证用户ID（内部使用）", hidden = true)
            @RequestHeader(value = HEADER_AUTH_USER_ID, required = false) Long authUserId,
            @Parameter(description = "用户ID")
            @RequestParam(required = false) Long userId,
            @Parameter(description = "商品ID")
            @RequestParam(required = false) Long productId,
            @Parameter(description = "行为类型：view/click/cart/favorite/buy")
            @RequestParam(required = false) String behaviorType) {
        Long pid = productId;
        Long uid = userId;
        String type = behaviorType;
        Long verifiedUserId = (authUserId != null) ? authUserId : uid;
        if (verifiedUserId == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", 400,
                    "message", "无法确认用户身份，请先登录"
            ));
        }
        recommendationService.recordBehavior(verifiedUserId, pid, type);
        return ResponseEntity.ok(Map.of("message", "行为记录成功"));
    }

    @PostMapping("/exposure")
    @Operation(
        summary = "记录商品曝光",
        description = "记录用户对推荐商品的曝光行为，用于曝光负采样和在线学习"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "记录成功"),
        @ApiResponse(responseCode = "400", description = "参数无效"),
        @ApiResponse(responseCode = "401", description = "用户未登录")
    })
    public ResponseEntity<Map<String, Object>> recordExposure(
            @Parameter(description = "网关认证用户ID（内部使用）", hidden = true)
            @RequestHeader(value = HEADER_AUTH_USER_ID, required = false) Long authUserId,
            @Parameter(description = "用户ID")
            @RequestParam Long userId,
            @Parameter(description = "商品ID")
            @RequestParam Long productId,
            @Parameter(description = "推荐位排名（从1开始）")
            @RequestParam(required = false, defaultValue = "0") Integer position,
            @Parameter(description = "推荐来源：deepfm/cf/popular")
            @RequestParam(required = false, defaultValue = "deepfm") String recommendType) {
        Long verifiedUserId = (authUserId != null) ? authUserId : userId;
        exposureService.recordExposure(verifiedUserId, productId, position, recommendType);
        return ResponseEntity.ok(Map.of("message", "曝光记录成功"));
    }

    @PostMapping("/exposure/batch")
    @Operation(
        summary = "批量记录商品曝光",
        description = "一次性记录多个商品的曝光（用于推荐结果返回时）"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "记录成功"),
        @ApiResponse(responseCode = "400", description = "参数无效"),
        @ApiResponse(responseCode = "401", description = "用户未登录")
    })
    public ResponseEntity<Map<String, Object>> recordExposures(
            @Parameter(description = "网关认证用户ID（内部使用）", hidden = true)
            @RequestHeader(value = HEADER_AUTH_USER_ID, required = false) Long authUserId,
            @RequestBody Map<String, Object> request) {
        Long authId = (authUserId != null) ? authUserId : null;
        Long userId;
        Object uidObj = request.get("userId");
        if (uidObj instanceof Number) {
            userId = ((Number) uidObj).longValue();
        } else {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "userId 无效"));
        }
        Long verifiedUserId = (authId != null) ? authId : userId;

        @SuppressWarnings("unchecked")
        List<Number> productIdsRaw = (List<Number>) request.get("productIds");
        if (productIdsRaw == null || productIdsRaw.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "productIds 不能为空"));
        }
        List<Long> productIds = productIdsRaw.stream().map(Number::longValue).toList();

        String recommendType = (String) request.getOrDefault("recommendType", "deepfm");
        exposureService.recordExposures(verifiedUserId, productIds, recommendType);
        return ResponseEntity.ok(Map.of(
                "message", "批量曝光记录成功",
                "count", productIds.size()
        ));
    }

    @GetMapping("/exposure/samples")
    @Operation(
        summary = "获取曝光负样本",
        description = "查询用户曝光但未点击的商品列表（用于训练负采样）"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "查询成功"),
        @ApiResponse(responseCode = "401", description = "用户未登录")
    })
    public ResponseEntity<Map<String, Object>> getExposureSamples(
            @Parameter(description = "网关认证用户ID（内部使用）", hidden = true)
            @RequestHeader(value = HEADER_AUTH_USER_ID, required = false) Long authUserId,
            @Parameter(description = "用户ID")
            @RequestParam Long userId,
            @Parameter(description = "需要排除的商品ID列表")
            @RequestParam(required = false) String excludeItems,
            @Parameter(description = "返回数量上限", example = "20")
            @RequestParam(defaultValue = "20") Integer limit) {
        Long verifiedUserId = (authUserId != null) ? authUserId : userId;
        java.util.Set<Long> excludeSet = new java.util.HashSet<>();
        if (excludeItems != null && !excludeItems.isBlank()) {
            for (String s : excludeItems.split(",")) {
                try { excludeSet.add(Long.parseLong(s.trim())); } catch (Exception ignored) {}
            }
        }
        List<Long> samples = exposureService.getExposureNegativeSamples(verifiedUserId, excludeSet, limit);
        return ResponseEntity.ok(Map.of(
                "userId", verifiedUserId,
                "negativeSamples", samples,
                "count", samples.size()
        ));
    }

    @PostMapping("/refresh")
    @Operation(
        summary = "刷新推荐缓存",
        description = "清除推荐服务的所有 Redis 缓存（相似度矩阵、热门列表、个性化推荐结果）"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "刷新成功")
    })
    public ResponseEntity<Map<String, String>> refreshCache() {
        recommendationService.refreshRecommendationCache();
        return ResponseEntity.ok(Map.of("message", "缓存刷新成功"));
    }

    @GetMapping("/baseline/compare")
    @Operation(
        summary = "推荐算法基线对比",
        description = "对热门基线、ItemCF二值加权、ItemCF评分加权三种推荐算法进行离线评估，返回 Precision@K、Recall@K、NDCG@K、HitRate@K 指标"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "评估完成")
    })
    public ResponseEntity<Map<String, Object>> compareBaselines(
            @Parameter(description = "Top-K 评估指标", example = "10")
            @RequestParam(defaultValue = "10") Integer topK,
            @Parameter(description = "评估样本用户数量", example = "200")
            @RequestParam(defaultValue = "200") Integer sampleUsers) {
        Map<String, Object> result = recommendationService.compareBaselines(topK, sampleUsers);
        return ResponseEntity.ok(result);
    }

    // ========== 灰度发布相关接口 ==========

    @GetMapping("/gray/status")
    @Operation(
        summary = "获取灰度发布状态",
        description = "查询灰度开关是否开启，以及灰度流量比例"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "查询成功"),
        @ApiResponse(responseCode = "401", description = "用户未登录")
    })
    public ResponseEntity<Map<String, Object>> getGrayStatus(
            @Parameter(description = "网关认证用户ID（内部使用）", hidden = true)
            @RequestHeader(value = HEADER_AUTH_USER_ID, required = false) Long authUserId) {
        if (authUserId == null) {
            return ResponseEntity.status(401).body(Map.of(
                    "code", 401,
                    "message", "请先登录"
            ));
        }
        Map<String, Object> result = new HashMap<>();
        result.put("enabled", grayReleaseService.isGrayEnabled());
        result.put("ratio", grayReleaseService.getGrayRatio());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/gray/check")
    @Operation(
        summary = "检查用户灰度分组",
        description = "判断当前用户是否在灰度组（使用 DeepFM 重排）还是对照组（使用 ItemCF）"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "查询成功")
    })
    public ResponseEntity<Map<String, Object>> checkGrayUser(
            @Parameter(description = "网关认证用户ID（内部使用）", hidden = true)
            @RequestHeader(value = HEADER_AUTH_USER_ID, required = false) Long authUserId,
            @Parameter(description = "用户ID")
            @RequestParam Long userId) {
        Long verifiedUserId = (authUserId != null) ? authUserId : userId;
        boolean isGray = grayReleaseService.isGrayUser(verifiedUserId);
        Map<String, Object> result = new HashMap<>();
        result.put("userId", verifiedUserId);
        result.put("isGray", isGray);
        result.put("algorithm", isGray ? "deepfm" : "itemcf");
        return ResponseEntity.ok(result);
    }

    @GetMapping("/gray/metrics")
    @Operation(
        summary = "获取灰度指标",
        description = "获取灰度实验的详细指标数据（曝光数、点击数、加购数、下单数）"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "查询成功")
    })
    public ResponseEntity<Map<String, Object>> getGrayMetrics(
            @Parameter(description = "查询日期（格式：yyyy-MM-dd），不填则查当天")
            @RequestParam(required = false) String date) {
        Map<String, Object> result = grayReleaseService.getMetrics(date);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/gray/compare")
    @Operation(
        summary = "灰度组与对照组对比",
        description = "对比灰度组（DeepFM）和对照组（ItemCF）的 CTR、加购率、下单率等指标"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "查询成功")
    })
    public ResponseEntity<Map<String, Object>> compareGrayMetrics(
            @Parameter(description = "查询日期（格式：yyyy-MM-dd），不填则查当天")
            @RequestParam(required = false) String date) {
        Map<String, Object> result = grayReleaseService.compareMetrics(date);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/gray/click")
    @Operation(
        summary = "记录推荐商品点击",
        description = "记录用户点击推荐商品的行为，用于灰度实验效果评估"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "记录成功"),
        @ApiResponse(responseCode = "401", description = "用户未登录")
    })
    public ResponseEntity<Map<String, Object>> recordClick(
            @Parameter(description = "网关认证用户ID（内部使用）", hidden = true)
            @RequestHeader(value = HEADER_AUTH_USER_ID, required = false) Long authUserId,
            @Parameter(description = "用户ID")
            @RequestParam Long userId,
            @Parameter(description = "推荐算法：deepfm 或 itemcf")
            @RequestParam String algorithm,
            @Parameter(description = "推荐列表中的位置（从0开始）", example = "0")
            @RequestParam(defaultValue = "0") Integer position,
            @Parameter(description = "商品ID")
            @RequestParam(required = false) Long itemId) {
        Long verifiedUserId = (authUserId != null) ? authUserId : userId;
        grayReleaseService.recordClick(verifiedUserId, algorithm, position, itemId);
        return ResponseEntity.ok(Map.of("message", "点击记录成功"));
    }

    @PostMapping("/gray/cart")
    @Operation(
        summary = "记录推荐商品加购",
        description = "记录用户将推荐商品加入购物车的行为"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "记录成功"),
        @ApiResponse(responseCode = "401", description = "用户未登录")
    })
    public ResponseEntity<Map<String, Object>> recordCart(
            @Parameter(description = "网关认证用户ID（内部使用）", hidden = true)
            @RequestHeader(value = HEADER_AUTH_USER_ID, required = false) Long authUserId,
            @Parameter(description = "用户ID")
            @RequestParam Long userId,
            @Parameter(description = "推荐算法：deepfm 或 itemcf")
            @RequestParam String algorithm,
            @Parameter(description = "商品ID")
            @RequestParam(required = false) Long itemId) {
        Long verifiedUserId = (authUserId != null) ? authUserId : userId;
        grayReleaseService.recordCart(verifiedUserId, algorithm, itemId);
        return ResponseEntity.ok(Map.of("message", "加购记录成功"));
    }

    @PostMapping("/gray/order")
    @Operation(
        summary = "记录推荐商品下单",
        description = "记录用户通过推荐下单的行为，包含下单金额"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "记录成功"),
        @ApiResponse(responseCode = "401", description = "用户未登录")
    })
    public ResponseEntity<Map<String, Object>> recordOrder(
            @Parameter(description = "网关认证用户ID（内部使用）", hidden = true)
            @RequestHeader(value = HEADER_AUTH_USER_ID, required = false) Long authUserId,
            @Parameter(description = "用户ID")
            @RequestParam Long userId,
            @Parameter(description = "推荐算法：deepfm 或 itemcf")
            @RequestParam String algorithm,
            @Parameter(description = "商品ID")
            @RequestParam(required = false) Long itemId,
            @Parameter(description = "下单金额")
            @RequestParam(defaultValue = "0") Double amount) {
        Long verifiedUserId = (authUserId != null) ? authUserId : userId;
        grayReleaseService.recordOrder(verifiedUserId, algorithm, itemId, amount);
        return ResponseEntity.ok(Map.of("message", "下单记录成功"));
    }

    // ========== A/B Testing 实验管理（需用户身份）==========

    @PostMapping("/experiment/create")
    @Operation(
        summary = "创建A/B实验",
        description = "创建一个新的A/B测试实验，指定实验名称、变体列表和流量分配比例"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "创建成功"),
        @ApiResponse(responseCode = "401", description = "用户未登录")
    })
    public ResponseEntity<Map<String, Object>> createExperiment(
            @Parameter(description = "网关认证用户ID（内部使用）", hidden = true)
            @RequestHeader(value = HEADER_AUTH_USER_ID, required = false) Long authUserId,
            @RequestBody Map<String, Object> request) {
        if (authUserId == null) {
            return ResponseEntity.status(401).body(Map.of("code", 401, "message", "请先登录"));
        }
        String name = (String) request.get("name");
        @SuppressWarnings("unchecked")
        List<String> variants = (List<String>) request.get("variants");
        Integer traffic = (Integer) request.get("trafficPercent");
        String description = (String) request.get("description");

        Map<String, Object> experiment = experimentService.createExperiment(name, variants, traffic, description);
        return ResponseEntity.ok(experiment);
    }

    @GetMapping("/experiment/list")
    @Operation(
        summary = "获取实验列表",
        description = "获取所有A/B实验的列表，包含每个实验的状态和流量配置"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "查询成功"),
        @ApiResponse(responseCode = "401", description = "用户未登录")
    })
    public ResponseEntity<List<Map<String, Object>>> listExperiments(
            @Parameter(description = "网关认证用户ID（内部使用）", hidden = true)
            @RequestHeader(value = HEADER_AUTH_USER_ID, required = false) Long authUserId) {
        if (authUserId == null) {
            return ResponseEntity.status(401).body(List.of(Map.of("code", 401, "message", "请先登录")));
        }
        List<Map<String, Object>> experiments = experimentService.listExperiments();
        return ResponseEntity.ok(experiments);
    }

    @GetMapping("/experiment/{id}")
    @Operation(
        summary = "获取实验详情",
        description = "根据实验ID获取单个A/B实验的详细信息"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "查询成功"),
        @ApiResponse(responseCode = "404", description = "实验不存在"),
        @ApiResponse(responseCode = "401", description = "用户未登录")
    })
    public ResponseEntity<Map<String, Object>> getExperiment(
            @Parameter(description = "网关认证用户ID（内部使用）", hidden = true)
            @RequestHeader(value = HEADER_AUTH_USER_ID, required = false) Long authUserId,
            @Parameter(description = "实验ID", required = true)
            @PathVariable String id) {
        if (authUserId == null) {
            return ResponseEntity.status(401).body(Map.of("code", 401, "message", "请先登录"));
        }
        Map<String, Object> exp = experimentService.getExperiment(id);
        if (exp == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(exp);
    }

    @GetMapping("/experiment/{id}/stats")
    @Operation(
        summary = "获取实验统计数据",
        description = "获取A/B实验各变体的详细统计数据（曝光量、点击量、转化率等）"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "查询成功"),
        @ApiResponse(responseCode = "401", description = "用户未登录")
    })
    public ResponseEntity<Map<String, Object>> getExperimentStats(
            @Parameter(description = "网关认证用户ID（内部使用）", hidden = true)
            @RequestHeader(value = HEADER_AUTH_USER_ID, required = false) Long authUserId,
            @Parameter(description = "实验ID", required = true)
            @PathVariable String id) {
        if (authUserId == null) {
            return ResponseEntity.status(401).body(Map.of("code", 401, "message", "请先登录"));
        }
        Map<String, Object> stats = experimentService.getVariantStats(id);
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/experiment/user/{userId}")
    @Operation(
        summary = "获取用户的实验分配",
        description = "查询指定用户被分配到哪些A/B实验的哪个变体"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "查询成功"),
        @ApiResponse(responseCode = "401", description = "用户未登录")
    })
    public ResponseEntity<Map<String, String>> getUserVariants(
            @Parameter(description = "网关认证用户ID（内部使用）", hidden = true)
            @RequestHeader(value = HEADER_AUTH_USER_ID, required = false) Long authUserId,
            @Parameter(description = "用户ID", required = true)
            @PathVariable Long userId) {
        if (authUserId == null) {
            return ResponseEntity.status(401).body(Map.of("code", "401", "message", "请先登录"));
        }
        Map<String, String> variants = experimentService.getAllVariants(userId);
        return ResponseEntity.ok(variants);
    }

    @PostMapping("/experiment/{id}/end")
    @Operation(
        summary = "结束A/B实验",
        description = "提前终止指定实验，标记为已完成状态"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "实验已结束"),
        @ApiResponse(responseCode = "401", description = "用户未登录")
    })
    public ResponseEntity<Map<String, String>> endExperiment(
            @Parameter(description = "网关认证用户ID（内部使用）", hidden = true)
            @RequestHeader(value = HEADER_AUTH_USER_ID, required = false) Long authUserId,
            @Parameter(description = "实验ID", required = true)
            @PathVariable String id) {
        if (authUserId == null) {
            return ResponseEntity.status(401).body(Map.of("code", "401", "message", "请先登录"));
        }
        experimentService.endExperiment(id);
        return ResponseEntity.ok(Map.of("message", "实验已结束"));
    }

    @DeleteMapping("/experiment/{id}")
    @Operation(
        summary = "删除A/B实验",
        description = "删除指定的A/B实验（仅允许删除已结束的实验）"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "删除成功"),
        @ApiResponse(responseCode = "400", description = "实验仍在进行中，无法删除"),
        @ApiResponse(responseCode = "401", description = "用户未登录")
    })
    public ResponseEntity<Map<String, String>> deleteExperiment(
            @Parameter(description = "网关认证用户ID（内部使用）", hidden = true)
            @RequestHeader(value = HEADER_AUTH_USER_ID, required = false) Long authUserId,
            @Parameter(description = "实验ID", required = true)
            @PathVariable String id) {
        if (authUserId == null) {
            return ResponseEntity.status(401).body(Map.of("code", "401", "message", "请先登录"));
        }
        experimentService.deleteExperiment(id);
        return ResponseEntity.ok(Map.of("message", "实验已删除"));
    }

    // ========== 用户画像（需用户身份）==========

    @GetMapping("/profile/{userId}")
    @Operation(
        summary = "获取用户画像",
        description = "获取用户的偏好标签体系，包括类目偏好、品牌偏好、活跃度等维度"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "查询成功"),
        @ApiResponse(responseCode = "401", description = "用户未登录")
    })
    public ResponseEntity<Map<String, Object>> getUserProfile(
            @Parameter(description = "网关认证用户ID（内部使用）", hidden = true)
            @RequestHeader(value = HEADER_AUTH_USER_ID, required = false) Long authUserId,
            @Parameter(description = "用户ID", required = true)
            @PathVariable Long userId) {
        if (authUserId == null) {
            return ResponseEntity.status(401).body(Map.of("code", 401, "message", "请先登录"));
        }
        Map<String, Object> profile = userProfileService.getProfile(userId);
        if (profile.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "userId", userId,
                    "message", "暂无用户画像数据"
            ));
        }
        profile.put("userId", userId);
        return ResponseEntity.ok(profile);
    }

    @PostMapping("/profile/{userId}/refresh")
    @Operation(
        summary = "刷新用户画像",
        description = "强制重新计算并更新指定用户的偏好画像标签"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "刷新成功"),
        @ApiResponse(responseCode = "401", description = "用户未登录")
    })
    public ResponseEntity<Map<String, String>> refreshProfile(
            @Parameter(description = "网关认证用户ID（内部使用）", hidden = true)
            @RequestHeader(value = HEADER_AUTH_USER_ID, required = false) Long authUserId,
            @Parameter(description = "用户ID", required = true)
            @PathVariable Long userId) {
        if (authUserId == null) {
            return ResponseEntity.status(401).body(Map.of("code", "401", "message", "请先登录"));
        }
        userProfileService.buildFullProfile(userId);
        return ResponseEntity.ok(Map.of("message", "画像刷新成功"));
    }
}
