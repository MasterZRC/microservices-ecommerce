"""
离线评测模块
支持三种算法的离线评估：DeepFM / ItemCF / 热门
评测指标：Precision@K、Recall@K、NDCG@K、MRR@K、AUC、LogLoss
"""
import numpy as np
import logging
from typing import Dict, List, Optional, Tuple
from collections import defaultdict

logger = logging.getLogger(__name__)


def compute_auc_and_logloss(
    y_true: List[int],
    y_prob: List[float]
) -> Dict[str, float]:
    """
    计算 AUC 和 LogLoss（不依赖 sklearn，用于无 sklearn 环境）
    """
    y_true = np.array(y_true, dtype=np.float32)
    y_prob = np.array(y_prob, dtype=np.float32)

    n = len(y_true)
    pos_count = y_true.sum()
    neg_count = n - pos_count

    # LogLoss
    eps = 1e-7
    clipped = np.clip(y_prob, eps, 1 - eps)
    logloss = float(-np.mean(
        y_true * np.log(clipped) + (1 - y_true) * np.log(1 - clipped)
    ))

    # AUC（Wilcoxon-Mann-Whitney）
    auc = 0.0
    if pos_count > 0 and neg_count > 0:
        # 正样本分数 > 负样本分数的占比
        pos_probs = y_prob[y_true == 1]
        neg_probs = y_prob[y_true == 0]
        auc = float(np.mean(
            pos_probs[:, None] > neg_probs[None, :]
        ))

    return {"auc": auc, "logloss": logloss}


def compute_ranking_metrics(
    ranked_items: List[int],
    positive_items: set,
    k: int = 10
) -> Dict[str, float]:
    """
    计算排序相关指标（Precision@K、Recall@K、NDCG@K、MRR@K）

    Args:
        ranked_items: 排序后的商品 ID 列表（0-indexed）
        positive_items: 正样本商品 ID 集合
        k: 截断位置
    """
    if not positive_items:
        return {"precision_at_k": 0.0, "recall_at_k": 0.0,
                "ndcg_at_k": 0.0, "mrr_at_k": 0.0}

    ranked_k = ranked_items[:k]
    hits = set(ranked_k) & positive_items
    hit_count = len(hits)

    # Precision@K
    precision = hit_count / k if k > 0 else 0.0

    # Recall@K
    recall = hit_count / len(positive_items) if len(positive_items) > 0 else 0.0

    # NDCG@K
    dcg = 0.0
    for i, item_id in enumerate(ranked_k):
        if item_id in positive_items:
            dcg += 1.0 / np.log2(i + 2)  # i+2 因为 i 从 0 开始

    ideal_ranked = sorted(list(positive_items), key=lambda x: 1)  # 理想序，正样本都在前面
    idcg = sum(1.0 / np.log2(i + 2) for i in range(min(len(ideal_ranked), k)))

    ndcg = dcg / idcg if idcg > 0 else 0.0

    # MRR@K（Mean Reciprocal Rank）
    mrr = 0.0
    for i, item_id in enumerate(ranked_k):
        if item_id in positive_items:
            mrr = 1.0 / (i + 1)
            break

    return {
        "precision_at_k": float(precision),
        "recall_at_k": float(recall),
        "ndcg_at_k": float(ndcg),
        "mrr_at_k": float(mrr),
    }


def offline_evaluate_deepfm(
    model,          # DeepFMRanker 实例
    user_features_list: List[Dict],
    item_features_list: List[Dict],
    labels: List[int],
    k: int = 10,
    test_ratio: float = 0.2
) -> Dict[str, float]:
    """
    DeepFM 离线评测：
    1. 按时间划分训练/测试集（用前 80% 时间样本训练，后 20% 测试）
    2. 评测 CTR 预估指标（AUC、LogLoss）
    3. 评测 Top-K 排序指标（按 CTR 分数排序后）

    Args:
        model: 已加载权重的 DeepFMRanker 实例
        user_features_list: 用户特征列表
        item_features_list: 商品特征列表
        labels: 标签列表 (0/1)
        k: 排序截断 K
        test_ratio: 测试集比例

    Returns:
        {auc, logloss, precision_at_k, recall_at_k, ndcg_at_k, mrr_at_k, ...}
    """
    n = len(labels)
    if n < 10:
        logger.warning(f"样本量太少 ({n})，无法进行可靠评测")
        return {"error": f"样本量 {n} < 10"}

    split_idx = int(n * (1 - test_ratio))

    # 测试集：保留全部样本做 CTR 预估评估
    test_labels = labels[split_idx:]
    test_user_feats = user_features_list[split_idx:]
    test_item_feats = item_features_list[split_idx:]

    if len(test_labels) < 5:
        return {"error": f"测试集样本量 {len(test_labels)} < 5"}

    # CTR 预估指标
    item_feat_dict = {str(i): feat for i, feat in enumerate(test_item_feats)}
    # 构建 user_id 虚拟列表（评测时每个样本视为不同用户，取共同用户特征）
    avg_user_feat = _average_user_features(test_user_feats)

    scores = model.rank(avg_user_feat, item_feat_dict)

    ctr_metrics = compute_auc_and_logloss(test_labels, scores)

    # Top-K 排序指标
    ranked_indices = sorted(range(len(scores)), key=lambda i: scores[i], reverse=True)
    ranked_items = [int(test_item_feats[i].get("item_id", i)) for i in ranked_indices]
    positive_items = {int(test_item_feats[i].get("item_id", i))
                     for i in range(len(test_labels)) if test_labels[i] == 1}

    ranking_metrics = compute_ranking_metrics(ranked_items, positive_items, k)

    result = {**ctr_metrics, **ranking_metrics}
    result["test_samples"] = len(test_labels)
    result["positive_rate"] = round(sum(test_labels) / len(test_labels), 4)

    return result


def offline_evaluate_itemcf(
    user_item_score_matrix: Dict[int, Dict[int, float]],
    similarity_matrix: Dict[int, Dict[int, float]],
    test_data: List[Tuple[int, int, int]],
    # test_data: [(user_id, item_id, label), ...]  label=1 为正样本
    k: int = 10
) -> Dict[str, float]:
    """
    ItemCF 离线评测（留一法）：
    按用户分组，每用户随机留一个正样本为测试集
    用其余正样本作为用户历史，计算 ItemCF 得分并排序

    Args:
        user_item_score_matrix: {user_id: {item_id: score}}
        similarity_matrix: {item_i: {item_j: sim_score}}
        test_data: [(user_id, item_id, label=1)]
        k: 排序截断 K

    Returns:
        {precision_at_k, recall_at_k, ndcg_at_k, mrr_at_k, hit_rate}
    """
    if not test_data:
        return {"error": "测试数据为空"}

    ranked_all = []
    positive_all = set()

    for user_id, item_id, label in test_data:
        if label != 1:
            continue

        user_history = user_item_score_matrix.get(user_id, {})
        if item_id in user_history:
            user_history = {k: v for k, v in user_history.items() if k != item_id}

        if not user_history:
            continue

        # 计算候选得分
        candidate_scores = defaultdict(float)
        for hist_item, hist_score in user_history.items():
            sim_map = similarity_matrix.get(hist_item, {})
            for cand, sim in sim_map.items():
                if cand != item_id:  # 排除测试正样本本身
                    candidate_scores[cand] += hist_score * sim

        ranked = sorted(candidate_scores.items(), key=lambda x: x[1], reverse=True)
        ranked_items = [cand for cand, _ in ranked[:k]]

        ranked_all.append(ranked_items)
        positive_all.add(item_id)

    # 聚合所有用户的评测结果
    total_users = len(ranked_all)
    if total_users == 0:
        return {"error": "没有有效测试用户"}

    precisions, recalls, ndcgs, mrrs, hits = [], [], [], [], []

    for ranked_items, pos_item in zip(ranked_all, positive_all):
        metrics = compute_ranking_metrics(ranked_items, {pos_item}, k)
        precisions.append(metrics["precision_at_k"])
        recalls.append(metrics["recall_at_k"])
        ndcgs.append(metrics["ndcg_at_k"])
        mrrs.append(metrics["mrr_at_k"])
        hits.append(1.0 if pos_item in set(ranked_items) else 0.0)

    return {
        "precision_at_k": round(float(np.mean(precisions)), 4),
        "recall_at_k": round(float(np.mean(recalls)), 4),
        "ndcg_at_k": round(float(np.mean(ndcgs)), 4),
        "mrr_at_k": round(float(np.mean(mrrs)), 4),
        "hit_rate": round(float(np.mean(hits)), 4),
        "total_users": total_users,
    }


def offline_evaluate_popular(
    popular_items: List[int],
    test_data: List[Tuple[int, int, int]],
    k: int = 10
) -> Dict[str, float]:
    """
    热门基线离线评测：
    直接推荐全局热门商品，不做个性化

    Returns:
        {precision_at_k, recall_at_k, ndcg_at_k, mrr_at_k, hit_rate}
    """
    if not test_data or not popular_items:
        return {"error": "数据为空"}

    pos_items = {item_id for _, item_id, label in test_data if label == 1}
    ranked_all, pos_all = [], []

    for user_id, _, _ in test_data:
        ranked_all.append(popular_items[:k])
        pos_all.append(pos_items)

    precisions, recalls, ndcgs, mrrs, hits = [], [], [], [], []
    for ranked_items, pos_set in zip(ranked_all, pos_all):
        metrics = compute_ranking_metrics(ranked_items, pos_set, k)
        precisions.append(metrics["precision_at_k"])
        recalls.append(metrics["recall_at_k"])
        ndcgs.append(metrics["ndcg_at_k"])
        mrrs.append(metrics["mrr_at_k"])
        hits.append(1.0 if any(p in set(ranked_items) for p in pos_set) else 0.0)

    return {
        "precision_at_k": round(float(np.mean(precisions)), 4),
        "recall_at_k": round(float(np.mean(recalls)), 4),
        "ndcg_at_k": round(float(np.mean(ndcgs)), 4),
        "mrr_at_k": round(float(np.mean(mrrs)), 4),
        "hit_rate": round(float(np.mean(hits)), 4),
        "total_users": len(ranked_all),
    }


def _average_user_features(features_list: List[Dict]) -> Dict:
    """对多个用户特征取平均值（用于评测时模拟单一用户）"""
    if not features_list:
        return {}

    numeric_keys = ["view_1d", "click_1d", "cart_1d", "buy_1d",
                    "view_7d", "click_7d", "cart_7d", "buy_7d",
                    "last_active_hours"]

    result = {}
    for key in numeric_keys:
        values = [f.get(key, 0) or 0 for f in features_list]
        result[key] = float(np.mean(values))

    result["prefer_category"] = []
    result["prefer_brand"] = []
    return result


def compare_algorithms(
    deepfm_metrics: Dict,
    itemcf_metrics: Dict,
    popular_metrics: Dict
) -> Dict[str, Dict]:
    """
    对比三种算法的评测结果，返回相对于热门的提升百分比
    """
    def _safe(val, default=0.0):
        return float(val) if val is not None else default

    popular_auc = _safe(popular_metrics.get("auc", 0))
    popular_ndcg = _safe(popular_metrics.get("ndcg_at_k", 0))

    def _improvement(metrics, baseline_val):
        if baseline_val == 0:
            return None
        val = _safe(metrics.get("auc", 0) or metrics.get("ndcg_at_k", 0))
        return round((val - baseline_val) / baseline_val * 100, 2)

    return {
        "deepfm": {
            "auc": round(_safe(deepfm_metrics.get("auc")), 4),
            "ndcg_at_k": round(_safe(deepfm_metrics.get("ndcg_at_k")), 4),
            "auc_vs_popular": _improvement(deepfm_metrics, popular_auc),
            "ndcg_vs_popular": _improvement(deepfm_metrics, popular_ndcg),
        },
        "itemcf": {
            "ndcg_at_k": round(_safe(itemcf_metrics.get("ndcg_at_k")), 4),
            "mrr_at_k": round(_safe(itemcf_metrics.get("mrr_at_k")), 4),
            "hit_rate": round(_safe(itemcf_metrics.get("hit_rate")), 4),
            "ndcg_vs_popular": _improvement(itemcf_metrics, popular_ndcg),
        },
        "popular": {
            "ndcg_at_k": round(_safe(popular_metrics.get("ndcg_at_k")), 4),
            "mrr_at_k": round(_safe(popular_metrics.get("mrr_at_k")), 4),
            "hit_rate": round(_safe(popular_metrics.get("hit_rate")), 4),
        }
    }
