# 推荐算法 Baseline Comparison（2026-03-14）

## 1. 对比目标

给出可复现的算法对比实验，比较以下三种推荐策略在 Top-K 任务下的效果：

- Popular Baseline（全局热门）
- ItemCF Binary Baseline（二值交互协同过滤）
- ItemCF Weighted（加权隐式反馈协同过滤，当前主算法）

## 2. 评估方法

- 数据来源：`user_behavior` 行为日志。
- 切分策略：用户级留一法（对每个用户留出 1 个测试商品，其余作为训练）。
- 指标：
  - Precision@K
  - Recall@K
  - NDCG@K
  - HitRate
- 评估接口：
  - `GET /api/recommendation/baseline/compare?topK=5&sampleUsers=100`

## 3. 本次实测结果

实测返回：

```json
{
  "topK": 5,
  "sampleUsers": 100,
  "usersEvaluated": 6,
  "metrics": {
    "popular": {
      "precisionAtK": 0.2,
      "recallAtK": 1.0,
      "ndcgAtK": 0.7103,
      "hitRate": 1.0
    },
    "itemCfBinary": {
      "precisionAtK": 0.0333,
      "recallAtK": 0.1667,
      "ndcgAtK": 0.1667,
      "hitRate": 0.1667
    },
    "itemCfWeighted": {
      "precisionAtK": 0.0333,
      "recallAtK": 0.1667,
      "ndcgAtK": 0.1667,
      "hitRate": 0.1667
    }
  }
}
```

## 4. 结果解读

1. 当前 `usersEvaluated=6`，样本偏小，评估结果波动会很大。
2. 小样本下，热门基线会天然占优（覆盖广）。
3. ItemCF 的优势通常在“行为规模更大、用户兴趣更分化”时才稳定体现。

## 5. 答辩建议

建议在答辩中强调：

- 系统已具备完整 Baseline Comparison 框架（可复现实验，不是口头对比）。
- 当前阶段已完成“算法升级 + 评估闭环搭建”。
- 下一步只需扩大行为样本（如埋点到浏览/加购/下单链路），即可得到更稳定的对比结论。
