# 推荐系统升级路径（保留现有 ItemCF，增量接入深度学习）

## 1. 结论先说

- 现有推荐系统继续保留，作为召回层与兜底层。
- 深度学习模型新增为重排层，不替换现有推荐服务。
- 最终架构是：多路召回 -> 深度重排 -> 多样性重排 -> 返回结果。

---

## 2. 当前系统哪些保留不动

以下模块作为基础能力保留：

1) recommendation-service 的行为采集与缓存机制
- user_behavior 入库
- Redis 缓存 recommendation:personal:* / recommendation:popular:*

2) ItemCF 召回
- ItemCFAlgorithm.computeItemSimilarityWeighted
- RecommendationService.getPersonalizedRecommendations

3) 热门与冷启动召回
- getPopularItems / getColdStartRecommendations

4) 前端行为上报链路
- view / click / cart / buy 事件上报

---

## 3. 新增模块（深度学习）

新增一个独立服务：recommendation-rank-service（Python + FastAPI + PyTorch）

职责：
- 接收 recommendation-service 给出的候选商品列表
- 对每个候选计算深度模型分数
- 返回重排后的候选列表

建议模型：
- 第一阶段：DeepFM（实现快、效果稳定）
- 第二阶段：DIN（对序列兴趣更好）

---

## 4. 目标架构（上线形态）

1) 召回层（recommendation-service 内）
- recall_cf: ItemCF 候选（例如 80）
- recall_popular: 热门候选（例如 40）
- recall_category: 同类目候选（例如 40）

2) 合并去重
- 形成 recall_pool（例如最多 120）

3) 重排层（recommendation-rank-service）
- 输入 user + candidates + features
- 输出每个商品 rank_score

4) 业务重排层（recommendation-service 内）
- 多样性约束：限制同类目、同品牌重复
- 新鲜度约束：兼顾新品

5) 最终返回
- Top N 商品明细

---

## 5. 代码改造点（按你当前仓库）

A. recommendation-service（Java）

新增：
- dto/RankRequest.java
- dto/RankResponse.java
- service/RankClientService.java（调用 Python rank 服务）
- service/CandidateRecallService.java（多路召回）

改造：
- RecommendationService.getPersonalizedProductDetails
  - 现在：直接按 ItemCF 结果拉详情
  - 目标：先多路召回，再调用 RankClientService 重排，再拉详情

配置新增（application.yaml）：
- services.rank.url: http://recommendation-rank-service:8010
- recommendation.rerank.enabled: true
- recommendation.rerank.timeout-ms: 200

B. recommendation-rank-service（Python）

新增目录建议：
- recommendation-rank-service/
  - app/main.py
  - app/model.py
  - app/features.py
  - app/schemas.py
  - models/deepfm.pt
  - requirements.txt
  - Dockerfile

接口：
- POST /rank
  - 请求：user profile + candidate list + sparse/dense features
  - 响应：candidateId + rankScore（降序）

C. 前端（可选改造）
- Home 页增加调试开关：显示当前结果来源（CF / 热门 / 深度重排）
- 仅用于开发环境，生产默认关闭

---

## 6. 特征设计（第一版）

用户特征：
- 近1/7/30天 view/click/cart/buy 计数
- 最近活跃时间间隔
- 用户偏好类目 TopK（按行为加权）

商品特征：
- category_id, brand_id
- price bucket（价格分桶）
- sales bucket（销量分桶）
- 热度分（近期行为量）

交叉特征：
- user_prefer_category == item_category
- user_prefer_brand == item_brand
- 行为序列最近一次与该类目的时间间隔

标签：
- 先用 click/add-cart/buy 的加权二分类标签
- buy 权重最高

---

## 7. 上线策略（灰度+回滚）

灰度策略：
- 10% 用户开启 recommendation.rerank.enabled=true
- 90% 走旧逻辑

观测指标：
- CTR、加购率、下单转化率、GMV
- 推荐空结果率
- 响应耗时 P95

回滚策略：
- 一键关闭 recommendation.rerank.enabled
- 立即回到纯 ItemCF + 热门兜底

---

## 8. 里程碑建议

M1（2-3天）：
- 完成多路召回 CandidateRecallService
- 补齐 category map 真正生效

M2（3-5天）：
- 训练 DeepFM 第一版
- recommendation-rank-service 可返回稳定分数

M3（2天）：
- Java 侧接入 rank 服务
- 增加开关与超时兜底

M4（2-3天）：
- 灰度验证与调参
- 输出对比报告（基线 vs 重排）

---

## 9. 为什么这条路线最稳

- 现有系统完全保留，可随时回退。
- 深度学习收益集中在排序层，投入产出比高。
- 同时具备工程可控性、答辩可解释性和业务可验证性。
