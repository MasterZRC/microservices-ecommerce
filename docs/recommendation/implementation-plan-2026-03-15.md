# 推荐系统深度学习升级 - 实施计划

## 实施顺序

### M1 阶段（2-3天）：多路召回 + 类目数据修复 ✅ 完成
- [x] 实现 CandidateRecallService 多路召回服务
- [x] 修复 buildItemCategoryMap() 接入真实商品类目数据
- [x] 配置多路召回参数
- [x] 测试验证通过

### M2 阶段（3-5天）：深度学习模型训练 ✅ 完成
- [x] 创建 recommendation-rank-service (Python + FastAPI)
- [x] 实现 DeepFM 模型推理
- [x] 完成特征工程设计
- [x] 测试验证通过

### M3 阶段（2天）：Java 服务接入 ✅ 完成
- [x] 新增 RankClientService 调用 Python 服务
- [x] 新增 DTO：RankRequest / RankResponse（内置于 RankClientService）
- [x] 配置开关与超时兜底
- [x] 测试验证

### M4 阶段（2-3天）：灰度验证 ✅ 完成
- [x] 添加灰度配置 (gray.enabled, gray.ratio)
- [x] 实现 GrayReleaseService 灰度分流服务
- [x] 实现灰度用户判断（基于用户ID哈希，一致性分组）
- [x] 集成灰度逻辑到 RecommendationService
- [x] 实现埋点日志记录（曝光、点击、加购、下单）
- [x] 创建灰度指标 API（metrics、compare）
- [ ] 灰度 10% 用户
- [ ] 监控 CTR、加购率、下单转化率
- [ ] 输出对比报告

---

## 技术架构

```
┌─────────────────────────────────────────────────────────────┐
│                      recommendation-service (Java)          │
├─────────────────────────────────────────────────────────────┤
│  召回层                    重排层              业务重排层    │
│  ┌─────────┐            ┌─────────┐        ┌─────────┐   │
│  │ recall_cf│  ───────> │  DeepFM │  ────> │ 多样性  │   │
│  │ (80个)   │            │  重排   │        │ 约束    │   │
│  ├─────────┤            └─────────┘        └─────────┘   │
│  │recall_pop│                                               │
│  │ (40个)   │                                               │
│  ├──────────┤                                               │
│  │recall_cat│                                               │
│  │ (40个)   │                                               │
│  └──────────┘                                               │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
              ┌───────────────────────────────┐
              │ recommendation-rank-service   │
              │ (Python + FastAPI + PyTorch)  │
              │ - DeepFM 模型推理              │
              │ - 特征工程计算                  │
              └───────────────────────────────┘
```

---

## 关键配置

```yaml
# application.yaml 新增配置
services:
  rank:
    url: ${RANK_SERVICE_URL:http://localhost:8010}

recommendation:
  rerank:
    enabled: ${RERANK_ENABLED:false}
    timeout-ms: 200
  recall:
    cf-count: 80
    popular-count: 40
    category-count: 40
    max-pool-size: 120
```

---

## 回滚策略

- 一键关闭 `recommendation.rerank.enabled`
- 立即回到纯 ItemCF + 热门兜底
