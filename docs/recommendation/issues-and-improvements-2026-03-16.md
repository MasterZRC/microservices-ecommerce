# 推荐系统问题清单 & 改进计划

> 创建时间: 2026-03-16
> 最后更新: 2026-03-16

---

## 一、当前系统状态

### 1.1 已实现功能

| 功能 | 状态 | 说明 |
|-----|------|------|
| 多路召回 | ✅ 完成 | ItemCF + 热门 + 分类 |
| 行为收集 | ✅ 完成 | 前端自动上报 view/click/cart/buy |
| 行为权重 | ✅ 完成 | view=1, click=2, cart=4, buy=8, favorite=5 |
| 时间衰减 | ✅ 完成 | 指数衰减 (0.94^天数) |
| 热门缓存 | ✅ 完成 | Redis 缓存 24小时 |
| 相似度缓存 | ✅ 完成 | Redis 缓存 24小时 |
| DeepFM 排序 | ✅ 完成 | 但未训练 |
| 灰度发布 | ✅ 完成 | 10% 用户 |

### 1.2 技术架构

```
用户请求 → 推荐服务 → 多路召回(85候选) → DeepFM排序 → 返回Top8
                    ↓
              ┌─────┴─────┐
              │  ItemCF   │  协同过滤 (基于相似商品)
              │  Popular  │  全局热门
              │  Category │  偏好类目
              └───────────┘
                    ↓
              DeepFM模型 (Python)
              - 输入: 用户特征(10维) + 商品特征(8维)
              - 输出: CTR 预测分数
```

---

## 二、严重问题 (🔴 需要立即解决)

### 2.1 行为数据量不足

**问题描述:**
- 当前仅有 500+ 条模拟行为数据
- 真实用户行为数据几乎为零
- 导致 ItemCF 召回效果极差

**影响:**
- 大部分用户召回为空，依赖热门兜底
- 推荐结果缺乏个性化

**解决方案:**
1. 接入真实用户行为数据
2. 增加数据生成脚本，模拟更真实的用户行为
3. 建立数据质量监控

### 2.2 DeepFM 模型未训练

**问题描述:**
- DeepFM 模型使用随机初始化权重
- 模型参数未经真实数据训练
- 排序结果没有实际意义

**影响:**
- 排序无法真正预测用户点击概率
- 灰度用户的推荐效果可能更差

**解决方案:**
1. 准备训练数据 (用户-商品交互数据)
2. 调用 `/data/generate` 生成训练集
3. 调用 `/train` 训练模型
4. 保存模型并加载推理

**训练命令:**
```bash
# 1. 生成训练数据
curl -X POST "http://localhost:8010/data/generate" \
  -H "Content-Type: application/json" \
  -d '{"num_samples": 10000, "num_users": 100, "num_items": 400}'

# 2. 训练模型
curl -X POST "http://localhost:8010/train" \
  -H "Content-Type: application/json" \
  -d '{"epochs": 50, "batch_size": 256}'

# 3. 保存模型
curl -X POST "http://localhost:8010/model/save" \
  -H "Content-Type: application/json" \
  -d '{"path": "/models/deepfm_v1.pth"}'
```

---

## 三、中等问题 (🟡 需要规划解决)

### 3.1 缓存机制不完善

**问题描述:**
- 按用户 ID 缓存推荐结果
- 用户产生新行为后，缓存未及时更新
- 可能推荐用户已购买的商品

**当前逻辑:**
```java
// 缓存1小时
redisTemplate.opsForValue().set(cacheKey, recommendations, 1, TimeUnit.HOURS);
```

**改进方向:**
1. 增加缓存版本号机制
2. 用户产生新行为时主动失效缓存
3. 考虑使用 LRU 缓存

### 3.2 无 A/B 测试框架

**问题描述:**
- 无法评估 DeepFM vs ItemCF 的实际效果
- 缺少点击率、转化率等核心指标

**改进方向:**
1. 接入 Prometheus + Grafana 监控
2. 记录曝光日志和点击日志
3. 计算 HR@K、MRR 等推荐指标
4. 建立实验对比分析

### 3.3 特征工程不足

**当前特征:**
```
用户特征:
- view_1d, click_1d, cart_1d, buy_1d
- view_7d, last_active_hours

商品特征:
- category_id, brand_id, price_bucket, sales_bucket
```

**缺失特征:**
- 用户实时特征 (当前 session 行为)
- 时间特征 (工作日/周末、时段)
- 上下文特征 (设备、来源)
- 用户画像特征 (年龄、性别 - 如有)

---

## 四、待优化功能 (🟢 锦上添花)

### 4.1 相似商品推荐

**现状:**
- 只有首页的猜你喜欢
- 缺少商品详情页的「看了又看」

**实现思路:**
- 基于 ItemCF 矩阵，找到最相似的 N 个商品
- 在商品详情页展示

### 4.2 购物车推荐

**现状:**
- 购物车页面无推荐

**实现思路:**
- 基于购物车中的商品，推荐配件或互补品
- 使用类目相关性 + 协同过滤

### 4.3 推荐理由

**现状:**
- 用户不知道为什么要推荐这个商品

**实现思路:**
- 「因为您看过 xxx」
- 「热门商品，同类都在买」
- 「与您收藏的商品相似」

---

## 五、代码位置索引

### 5.1 推荐服务 (Java)

| 文件 | 功能 |
|-----|------|
| `RecommendationService.java` | 主推荐逻辑 |
| `CandidateRecallService.java` | 多路召回 |
| `ItemCFAlgorithm.java` | 协同过滤算法 |
| `RankClientService.java` | 调用排序服务 |
| `GrayReleaseService.java` | 灰度发布 |

### 5.2 排序服务 (Python)

| 文件 | 功能 |
|-----|------|
| `model.py` | DeepFM 模型定义 |
| `features.py` | 特征工程 |
| `main.py` | API 接口 |

### 5.3 前端 (Vue)

| 文件 | 功能 |
|-----|------|
| `Home.vue` | 首页推荐展示 |
| `ProductDetail.vue` | 商品详情 + 行为上报 |
| `api/index.js` | 行为上报接口 |

---

## 六、下一步行动计划

### 优先级 1: 数据积累
- [ ] 生成更多模拟行为数据 (至少 10000 条)
- [ ] 确保用户 ID 覆盖真实注册用户
- [ ] 增加商品 ID 覆盖范围 (1-400)

### 优先级 2: 模型训练
- [ ] 生成训练数据
- [ ] 训练 DeepFM 模型
- [ ] 验证模型效果

### 优先级 3: 监控指标
- [ ] 接入推荐效果指标
- [ ] 建立 A/B 测试
- [ ] 搭建可视化看板

---

## 七、相关文档

- [实现计划](./implementation-plan-2026-03-15.md)
- [基线对比](./baseline-comparison-2026-03-14.md)
- [深度学习升级路径](./deep-learning-upgrade-path-2026-03-15.md)
