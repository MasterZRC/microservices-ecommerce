package com.ecommerce.recommendation.service;

import com.ecommerce.recommendation.algorithm.ItemCFAlgorithm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 推荐多样性服务
 * 
 * 生产级推荐系统必须控制结果多样性，避免用户看到千篇一律的内容。
 * 本服务实现了 MMR（Maximal Marginal Relevance）算法进行多样性打散。
 * 
 * 多样性维度：
 * - 类目多样性：同一类目商品不能连续出现过多
 * - 品牌多样性：同一品牌商品不能连续出现过多
 * - 价格区间多样性：不同价格区间的商品混合展示
 * 
 * MMR 公式：score = λ * relevance - (1-λ) * diversity
 * - λ 越接近 1，越注重相关性
 * - λ 越接近 0，越注重多样性
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiversityService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final CandidateRecallService candidateRecallService;

    @Value("${recommendation.diversity.mmr-lambda:0.6}")
    private double mmrLambda;

    @Value("${recommendation.diversity.max-consecutive-same-category:2}")
    private int maxConsecutiveSameCategory;

    @Value("${recommendation.diversity.max-consecutive-same-brand:3}")
    private int maxConsecutiveSameBrand;

    private static final String ITEM_CATEGORY_KEY = "recommendation:item:category:";
    private static final String ITEM_BRAND_KEY = "recommendation:item:brand:";
    private static final String ITEM_PRICE_KEY = "recommendation:item:price:";

    /**
     * MMR 多样性打散
     * 
     * @param items 候选商品列表（已按相关性排序）
     * @param scores 候选商品的原始相关性分数
     * @param limit 最终返回数量
     * @return 打散后的商品列表
     */
    public List<Long> diversityWithMMR(List<Long> items, Map<Long, Double> scores, int limit) {
        if (items == null || items.isEmpty() || items.size() <= 1) {
            return items;
        }

        // 获取商品特征
        Map<Long, Long> categoryMap = getItemCategories(items);
        Map<Long, String> brandMap = getItemBrands(items);
        Map<Long, Double> priceMap = getItemPrices(items);

        // 使用贪心算法进行多样性打散
        return greedyDiversity(items, scores, categoryMap, brandMap, priceMap, limit);
    }

    /**
     * 简单类目打散（用于轻度多样性控制）
     * 确保同一类目商品不会连续出现超过指定数量
     * 
     * 【改进】使用更保守的打散策略：
     * - 只交换相邻的重复商品，而不是完全重新排序
     * - 尽量保持原始顺序的相关性
     */
    public List<Long> shuffleByCategory(List<Long> ordered) {
        if (ordered == null || ordered.size() <= maxConsecutiveSameCategory) {
            return ordered;
        }

        Map<Long, Long> categoryMap = candidateRecallService.buildItemCategoryMap();
        if (categoryMap == null || categoryMap.isEmpty()) {
            return ordered;
        }

        // 浅拷贝以避免修改原列表
        List<Long> result = new ArrayList<>(ordered);
        int n = result.size();
        
        // 只做局部交换，不破坏整体顺序
        for (int i = 0; i < n - 1; i++) {
            Long cat1 = categoryMap.getOrDefault(result.get(i), -1L);
            Long cat2 = categoryMap.getOrDefault(result.get(i + 1), -1L);
            
            // 如果两个相邻商品类目相同，向后找不同类目的商品交换
            if (cat1.equals(cat2)) {
                for (int j = i + 2; j < n; j++) {
                    Long catJ = categoryMap.getOrDefault(result.get(j), -1L);
                    if (!catJ.equals(cat1)) {
                        // 交换位置
                        Long temp = result.set(i + 1, result.get(j));
                        result.set(j, temp);
                        break;
                    }
                }
            }
        }

        return result;
    }

    /**
     * 多维度多样性打散（类目 + 品牌）
     * 
     * 【重要改进】保留相关性优先：
     * - 只在有足够多样性候选时才进行打散
     * - 避免将用户偏好类目的商品推到末尾
     * - 最多保留 Top-N 原始排序不被破坏
     */
    public List<Long> shuffleByMultiDimensional(List<Long> ordered) {
        if (ordered == null || ordered.isEmpty() || ordered.size() <= 3) {
            return ordered;
        }

        // 获取类目分布
        Map<Long, Long> categoryMap = candidateRecallService.buildItemCategoryMap();
        if (categoryMap == null || categoryMap.isEmpty()) {
            return ordered;
        }

        // 统计各类目数量
        Map<Long, Long> categoryCount = new HashMap<>();
        for (Long itemId : ordered) {
            Long cat = categoryMap.getOrDefault(itemId, -1L);
            categoryCount.merge(cat, 1L, Long::sum);
        }

        // 找出最大类目占比
        long total = ordered.size();
        long maxCategoryCount = categoryCount.values().stream().mapToLong(Long::longValue).max().orElse(1);
        double maxCategoryRatio = (double) maxCategoryCount / total;

        // 【核心改进】如果最大类目占比超过 60%，说明候选池与用户偏好高度一致，直接保留原始排序
        // 对于个性化推荐，相关性 > 多样性
        if (maxCategoryRatio > 0.6) {
            log.info("[多样性打散] 候选池与用户偏好高度一致（最大类目占比={}），保留原始排序", String.format("%.1f%%", maxCategoryRatio * 100));
            return ordered;
        }

        // 正常情况：进行完整的多维度打散
        List<Long> categoryShuffled = shuffleByCategory(ordered);
        
        // 进一步做品牌级别的打散
        Map<Long, String> brandMap = getItemBrands(categoryShuffled);
        if (brandMap == null || brandMap.isEmpty()) {
            return categoryShuffled;
        }

        List<Long> result = new ArrayList<>();
        List<Long> pending = new ArrayList<>();
        int consecutiveCount = 0;
        String lastBrand = null;

        for (Long itemId : categoryShuffled) {
            String brand = brandMap.getOrDefault(itemId, "");

            if (brand.equals(lastBrand)) {
                consecutiveCount++;
            } else {
                consecutiveCount = 1;
                lastBrand = brand;
            }

            if (consecutiveCount > maxConsecutiveSameBrand) {
                // 尝试找替代
                Long replacement = findBrandReplacement(pending, lastBrand, brandMap);
                if (replacement != null) {
                    pending.remove(replacement);
                    result.add(replacement);
                    consecutiveCount = 1;
                    lastBrand = brandMap.getOrDefault(replacement, "");
                }
                pending.add(itemId);
            } else {
                result.add(itemId);
            }
        }

        if (!pending.isEmpty()) {
            result.addAll(pending);
        }

        return result;
    }

    /**
     * 从 pending 中找一个不同品牌的商品作为替代
     */
    private Long findBrandReplacement(List<Long> pending, String targetBrand, Map<Long, String> brandMap) {
        for (Long item : pending) {
            String brand = brandMap.getOrDefault(item, "");
            if (!brand.equals(targetBrand)) {
                return item;
            }
        }
        return null;
    }

    /**
     * 贪心多样性算法
     * 在每一步选择时，平衡相关性和多样性
     */
    private List<Long> greedyDiversity(
            List<Long> items,
            Map<Long, Double> scores,
            Map<Long, Long> categoryMap,
            Map<Long, String> brandMap,
            Map<Long, Double> priceMap,
            int limit) {

        Set<Long> selected = new LinkedHashSet<>();
        List<Long> remaining = new ArrayList<>(items);

        // 计算类目和品牌的权重（用于多样性惩罚）
        Map<Long, Integer> categoryCount = new HashMap<>();
        Map<String, Integer> brandCount = new HashMap<>();

        while (selected.size() < limit && !remaining.isEmpty()) {
            Long bestItem = null;
            double bestScore = -1;

            for (Long candidate : remaining) {
                double relevance = scores.getOrDefault(candidate, 0.0);
                double diversity = calculateDiversityPenalty(
                        candidate, selected, categoryCount, brandCount, categoryMap, brandMap, priceMap);

                // MMR 公式
                double mmrScore = mmrLambda * relevance + (1 - mmrLambda) * diversity;

                if (mmrScore > bestScore) {
                    bestScore = mmrScore;
                    bestItem = candidate;
                }
            }

            if (bestItem != null) {
                selected.add(bestItem);
                remaining.remove(bestItem);

                // 更新计数
                Long cat = categoryMap.get(bestItem);
                if (cat != null) {
                    categoryCount.merge(cat, 1, Integer::sum);
                }
                String brand = brandMap.get(bestItem);
                if (brand != null) {
                    brandCount.merge(brand, 1, Integer::sum);
                }
            } else {
                break;
            }
        }

        return new ArrayList<>(selected);
    }

    /**
     * 计算多样性惩罚分数
     * 惩罚与已选商品相似度高的候选商品
     */
    private double calculateDiversityPenalty(
            Long candidate,
            Set<Long> selected,
            Map<Long, Integer> categoryCount,
            Map<String, Integer> brandCount,
            Map<Long, Long> categoryMap,
            Map<Long, String> brandMap,
            Map<Long, Double> priceMap) {

        double penalty = 0.0;

        // 类目惩罚：同类目已有越多，惩罚越大
        Long candidateCat = categoryMap.get(candidate);
        if (candidateCat != null) {
            int catCount = categoryCount.getOrDefault(candidateCat, 0);
            penalty += catCount * 0.3; // 每个同类目加 0.3 惩罚
        }

        // 品牌惩罚
        String candidateBrand = brandMap.get(candidate);
        if (candidateBrand != null) {
            int brandCnt = brandCount.getOrDefault(candidateBrand, 0);
            penalty += brandCnt * 0.2; // 每个同品牌加 0.2 惩罚
        }

        // 价格区间惩罚（同一价格区间内的商品也进行惩罚）
        // 这里简化处理，实际可以按价格分桶
        if (priceMap != null) {
            double candidatePrice = priceMap.getOrDefault(candidate, 0.0);
            for (Long selectedItem : selected) {
                double selectedPrice = priceMap.getOrDefault(selectedItem, 0.0);
                if (candidatePrice > 0 && selectedPrice > 0) {
                    // 价格差小于 20% 认为在同一价格区间
                    if (Math.abs(candidatePrice - selectedPrice) / Math.max(candidatePrice, selectedPrice) < 0.2) {
                        penalty += 0.1;
                    }
                }
            }
        }

        return penalty;
    }

    /**
     * 获取商品类目映射
     */
    private Map<Long, Long> getItemCategories(List<Long> itemIds) {
        Map<Long, Long> categoryMap = new HashMap<>();
        
        for (Long itemId : itemIds) {
            try {
                String url = candidateRecallService.getProductServiceUrl() + "/api/product/" + itemId;
                @SuppressWarnings("unchecked")
                Map<String, Object> product = candidateRecallService.getRestTemplate()
                        .getForObject(url, Map.class);
                if (product != null && product.get("categoryId") != null) {
                    Object catId = product.get("categoryId");
                    if (catId instanceof Number) {
                        categoryMap.put(itemId, ((Number) catId).longValue());
                    }
                }
            } catch (Exception e) {
                log.debug("获取商品 {} 类目失败", itemId);
            }
        }

        return categoryMap;
    }

    /**
     * 获取商品品牌映射
     */
    private Map<Long, String> getItemBrands(List<Long> itemIds) {
        Map<Long, String> brandMap = new HashMap<>();
        
        for (Long itemId : itemIds) {
            try {
                String url = candidateRecallService.getProductServiceUrl() + "/api/product/" + itemId;
                @SuppressWarnings("unchecked")
                Map<String, Object> product = candidateRecallService.getRestTemplate()
                        .getForObject(url, Map.class);
                if (product != null && product.get("brand") != null) {
                    brandMap.put(itemId, String.valueOf(product.get("brand")));
                }
            } catch (Exception e) {
                log.debug("获取商品 {} 品牌失败", itemId);
            }
        }

        return brandMap;
    }

    /**
     * 获取商品价格映射
     */
    private Map<Long, Double> getItemPrices(List<Long> itemIds) {
        Map<Long, Double> priceMap = new HashMap<>();
        
        for (Long itemId : itemIds) {
            try {
                String url = candidateRecallService.getProductServiceUrl() + "/api/product/" + itemId;
                @SuppressWarnings("unchecked")
                Map<String, Object> product = candidateRecallService.getRestTemplate()
                        .getForObject(url, Map.class);
                if (product != null && product.get("price") != null) {
                    Object price = product.get("price");
                    if (price instanceof Number) {
                        priceMap.put(itemId, ((Number) price).doubleValue());
                    }
                }
            } catch (Exception e) {
                log.debug("获取商品 {} 价格失败", itemId);
            }
        }

        return priceMap;
    }

    /**
     * 计算推荐结果的多样性指标
     * 
     * @return 多样性指标 Map，包含：
     *         - category_entropy: 类目熵（越高越多样）
     *         - category_coverage: 类目覆盖率
     *         - brand_entropy: 品牌熵
     *         - price_variance: 价格方差
     */
    public Map<String, Double> calculateDiversityMetrics(List<Long> items) {
        Map<String, Double> metrics = new HashMap<>();

        if (items == null || items.isEmpty()) {
            metrics.put("category_entropy", 0.0);
            metrics.put("category_coverage", 0.0);
            metrics.put("brand_entropy", 0.0);
            metrics.put("price_variance", 0.0);
            return metrics;
        }

        Map<Long, Long> categoryMap = getItemCategories(items);
        Map<Long, String> brandMap = getItemBrands(items);
        Map<Long, Double> priceMap = getItemPrices(items);

        // 计算类目熵
        Map<Long, Integer> categoryCount = new HashMap<>();
        for (Long item : items) {
            Long cat = categoryMap.get(item);
            if (cat != null) {
                categoryCount.merge(cat, 1, Integer::sum);
            }
        }
        double categoryEntropy = calculateEntropy(categoryCount.values(), items.size());
        metrics.put("category_entropy", categoryEntropy);

        // 计算类目覆盖率
        double categoryCoverage = (double) categoryCount.size() / Math.max(1, getTotalCategories());
        metrics.put("category_coverage", categoryCoverage);

        // 计算品牌熵
        Map<String, Integer> brandCount = new HashMap<>();
        for (Long item : items) {
            String brand = brandMap.get(item);
            if (brand != null) {
                brandCount.merge(brand, 1, Integer::sum);
            }
        }
        double brandEntropy = calculateEntropy(brandCount.values(), items.size());
        metrics.put("brand_entropy", brandEntropy);

        // 计算价格方差
        List<Double> prices = priceMap.values().stream().filter(p -> p > 0).collect(Collectors.toList());
        if (prices.size() > 1) {
            double mean = prices.stream().mapToDouble(Double::doubleValue).sum() / prices.size();
            double variance = prices.stream()
                    .mapToDouble(p -> Math.pow(p - mean, 2))
                    .sum() / prices.size();
            metrics.put("price_variance", variance);
        } else {
            metrics.put("price_variance", 0.0);
        }

        return metrics;
    }

    /**
     * 计算香农熵
     */
    private double calculateEntropy(Collection<Integer> counts, int total) {
        if (total <= 0 || counts.isEmpty()) return 0.0;

        double entropy = 0.0;
        for (int count : counts) {
            if (count > 0) {
                double p = (double) count / total;
                entropy -= p * Math.log(p + 1e-10) / Math.log(2);
            }
        }
        return entropy;
    }

    /**
     * 获取总类目数
     */
    private int getTotalCategories() {
        try {
            String url = candidateRecallService.getProductServiceUrl() + "/api/product/category/list";
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> categories = candidateRecallService.getRestTemplate()
                    .getForObject(url, List.class);
            return categories != null ? categories.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
