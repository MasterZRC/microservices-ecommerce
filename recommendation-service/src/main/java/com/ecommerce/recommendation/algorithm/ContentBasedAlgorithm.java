package com.ecommerce.recommendation.algorithm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 基于商品标题 TF-IDF 的内容相似度召回
 *
 * 冷启动策略：当用户历史行为稀少时，通过分析其浏览商品的标题文本，
 * 计算 TF-IDF 向量，找出语义上最相似的新商品进行推荐。
 *
 * 流程：
 * 1. 从 product-service 拉取所有商品的标题
 * 2. 对标题进行中文分词（Jieba）
 * 3. 计算每种商品的 TF-IDF 向量（倒排索引加速）
 * 4. 对新商品计算与用户历史商品的余弦相似度
 * 5. 返回 Top-K 最相似商品
 */
@Slf4j
@Component
public class ContentBasedAlgorithm {

    // ============================================================
    //  内部数据结构
    // ============================================================

    /**
     * 单个商品的文本向量（用于相似度计算）
     */
    public static class TextVector {
        public final long itemId;
        public final Map<String, Double> tf;         // term -> TF 权重
        public final Map<String, Double> tfidf;      // term -> TF-IDF 权重
        public final double norm;                    // 向量 L2 模长

        public TextVector(long itemId, Map<String, Double> tfidf) {
            this.itemId = itemId;
            this.tf = new HashMap<>();
            this.tfidf = tfidf;
            this.norm = Math.sqrt(tfidf.values().stream()
                    .mapToDouble(v -> v * v).sum());
        }

        /** 计算两个向量的余弦相似度 */
        public static double cosineSimilarity(TextVector a, TextVector b) {
            if (a.norm < 1e-10 || b.norm < 1e-10) return 0.0;

            double dotProduct = a.tfidf.entrySet().stream()
                    .filter(e -> b.tfidf.containsKey(e.getKey()))
                    .mapToDouble(e -> e.getValue() * b.tfidf.get(e.getKey()))
                    .sum();

            return dotProduct / (a.norm * b.norm);
        }
    }

    // ============================================================
    //  核心算法：构建倒排索引 + TF-IDF 向量
    // ============================================================

    /**
     * 根据商品标题集合构建全局 TF-IDF 向量库
     *
     * @param itemTitles {itemId: title} 商品 ID 到标题的映射
     * @param globalDocFreq 全局文档频率 {term: 包含该词的商品数}
     * @param totalDocs 总商品数
     * @return {itemId: TextVector}
     */
    public static Map<Long, TextVector> buildTfidfVectors(
            Map<Long, String> itemTitles,
            Map<String, Integer> globalDocFreq,
            int totalDocs) {

        if (itemTitles == null || itemTitles.isEmpty()) {
            return Collections.emptyMap();
        }

        // 第一步：计算每个商品的词频（TF）
        Map<Long, Map<String, Integer>> rawTermFreq = new HashMap<>();
        for (Map.Entry<Long, String> entry : itemTitles.entrySet()) {
            long itemId = entry.getKey();
            String title = entry.getValue();
            Map<String, Integer> termCount = tokenizeAndCount(title);
            rawTermFreq.put(itemId, termCount);
        }

        // 第二步：计算 TF-IDF 向量
        Map<Long, TextVector> vectors = new HashMap<>();
        for (Map.Entry<Long, Map<String, Integer>> entry : rawTermFreq.entrySet()) {
            long itemId = entry.getKey();
            Map<String, Integer> termCount = entry.getValue();
            if (termCount.isEmpty()) continue;

            // 总词数用于归一化 TF
            int totalTerms = termCount.values().stream().mapToInt(Integer::intValue).sum();
            if (totalTerms == 0) continue;

            // 计算 TF-IDF
            Map<String, Double> tfidf = new HashMap<>();
            for (Map.Entry<String, Integer> termEntry : termCount.entrySet()) {
                String term = termEntry.getKey();
                double tf = (double) termEntry.getValue() / totalTerms;
                int df = globalDocFreq.getOrDefault(term, 1);
                // 平滑 IDF：log((N + 1) / (df + 1))，避免 df=0 时除零
                double idf = Math.log((totalDocs + 1.0) / (df + 1));
                tfidf.put(term, tf * idf);
            }

            vectors.put(itemId, new TextVector(itemId, tfidf));
        }

        return vectors;
    }

    /**
     * 从用户历史商品中提取关键词（用于找相似商品）
     *
     * @param userHistoryItemIds 用户历史浏览/点击的商品
     * @param itemVectors 全量商品 TF-IDF 向量
     * @param topN 返回最相关的 N 个关键词
     * @return 关键词列表（按重要性排序）
     */
    public static List<String> extractTopKeywords(
            List<Long> userHistoryItemIds,
            Map<Long, TextVector> itemVectors,
            int topN) {

        // 聚合用户历史商品的 TF-IDF 向量
        Map<String, Double> aggVector = new HashMap<>();
        for (Long itemId : userHistoryItemIds) {
            TextVector vec = itemVectors.get(itemId);
            if (vec == null) continue;
            for (Map.Entry<String, Double> e : vec.tfidf.entrySet()) {
                aggVector.merge(e.getKey(), e.getValue(), Double::sum);
            }
        }

        // 按权重排序，返回 Top-N 关键词
        return aggVector.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topN)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * 基于内容相似度召回：为用户推荐与其历史商品文本相似的新商品
     *
     * @param userHistoryItemIds 用户历史商品列表
     * @param candidateItemIds 候选商品池（通常是多路召回后的并集）
     * @param itemVectors 全量商品 TF-IDF 向量
     * @param limit 返回数量上限
     * @return 排序后的推荐商品 ID 列表
     */
    public static List<Long> recommendByContentSimilarity(
            List<Long> userHistoryItemIds,
            List<Long> candidateItemIds,
            Map<Long, TextVector> itemVectors,
            int limit) {

        if (userHistoryItemIds == null || userHistoryItemIds.isEmpty() ||
            itemVectors == null || itemVectors.isEmpty()) {
            return Collections.emptyList();
        }

        // 构建用户历史商品的聚合向量（平均池化）
        List<TextVector> historyVectors = userHistoryItemIds.stream()
                .map(itemVectors::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (historyVectors.isEmpty()) {
            return Collections.emptyList();
        }

        // 聚合方式：取各维度最大值（MM 策略，比平均更稳定）
        Map<String, Double> userProfile = new HashMap<>();
        Set<String> allTerms = new HashSet<>();
        historyVectors.forEach(v -> allTerms.addAll(v.tfidf.keySet()));

        for (String term : allTerms) {
            double maxWeight = historyVectors.stream()
                    .mapToDouble(v -> v.tfidf.getOrDefault(term, 0.0))
                    .max()
                    .orElse(0.0);
            userProfile.put(term, maxWeight);
        }

        // 用户画像向量归一化
        double userNorm = Math.sqrt(userProfile.values().stream()
                .mapToDouble(v -> v * v).sum());
        if (userNorm < 1e-10) return Collections.emptyList();

        Map<String, Double> normalizedProfile = userProfile.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue() / userNorm
                ));

        // 计算每个候选商品与用户画像的相似度
        Map<Long, Double> scores = new HashMap<>();
        for (Long itemId : candidateItemIds) {
            TextVector vec = itemVectors.get(itemId);
            if (vec == null || vec.norm < 1e-10) continue;

            // 跳过用户已交互过的商品
            if (userHistoryItemIds.contains(itemId)) continue;

            // 计算相似度：用户画像 vs 商品向量
            double dotProduct = normalizedProfile.entrySet().stream()
                    .filter(e -> vec.tfidf.containsKey(e.getKey()))
                    .mapToDouble(e -> e.getValue() * vec.tfidf.get(e.getKey()))
                    .sum();

            scores.put(itemId, dotProduct);
        }

        // 排序返回 Top-K
        return scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    // ============================================================
    //  中文分词（轻量实现，无需额外依赖）
    // ============================================================

    /**
     * 中文分词：将标题切分为词语列表，并统计词频
     *
     * 策略：
     * 1. 按字符级别 N-gram（2-gram）提取词语候选
     * 2. 过滤停用词（的、了、和、是、等）和单字
     * 3. 保留数字和英文词
     */
    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "和", "是", "在", "有", "我", "你", "他", "她", "它",
            "们", "这", "那", "个", "一", "不", "就", "也", "都", "而", "及",
            "与", "为", "之", "以", "等", "可", "但", "或", "把", "被", "让",
            "从", "到", "对", "着", "过", "将", "向", "于"
    );

    private static Map<String, Integer> tokenizeAndCount(String text) {
        Map<String, Integer> counts = new HashMap<>();

        if (text == null || text.isBlank()) {
            return counts;
        }

        String clean = text.trim().toLowerCase();

        // 策略1：提取连续中文字符（2-gram）
        StringBuilder sb = new StringBuilder();
        for (char c : clean.toCharArray()) {
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) {
                sb.append(c);
            } else if (Character.isDigit(c) || Character.isLetter(c)) {
                sb.append(c).append(' ');
            } else {
                sb.append(' ');
            }
        }

        String processed = sb.toString();

        // 提取 2-gram 和 3-gram
        for (int n = 2; n <= 3; n++) {
            for (int i = 0; i <= processed.length() - n; i++) {
                String token = processed.substring(i, i + n).trim();
                // 过滤停用词和长度不足的
                if (token.length() >= 2 && !STOP_WORDS.contains(token)) {
                    // 进一步过滤全角标点
                    if (!token.matches("[\\uff00-\\uffef]+")) {
                        counts.merge(token, 1, Integer::sum);
                    }
                }
            }
        }

        // 策略2：提取英文/数字词（连续字母或数字串）
        for (String word : processed.split("\\s+")) {
            word = word.trim();
            if (word.matches("[a-z0-9]{2,}")) {
                counts.merge(word, 1, Integer::sum);
            }
        }

        return counts;
    }

    /**
     * 计算全局文档频率（每个词出现在多少个文档中）
     *
     * @param itemTitles {itemId: title}
     * @return {term: docFreq}
     */
    public static Map<String, Integer> computeDocumentFrequency(Map<Long, String> itemTitles) {
        Map<String, Integer> docFreq = new HashMap<>();

        for (String title : itemTitles.values()) {
            Set<String> uniqueTerms = tokenizeAndCount(title).keySet();
            for (String term : uniqueTerms) {
                docFreq.merge(term, 1, Integer::sum);
            }
        }

        return docFreq;
    }
}
