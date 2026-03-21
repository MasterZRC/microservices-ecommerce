# recommendation-rank-service

推荐系统排序服务 - 基于 DeepFM 的 CTR 预估

## 技术栈

- Python 3.10+
- FastAPI
- PyTorch
- DeepFM

## 本地运行

```bash
# 安装依赖
pip install -r requirements.txt

# 启动服务
uvicorn app.main:app --host 0.0.0.0 --port 8010
```

## 功能特性

- **DeepFM 模型推理**: 对候选商品进行 CTR 预估排序
- **模型训练**: 支持在线训练，可生成合成数据或使用真实数据
- **特征工程**: 支持用户特征、商品特征、交叉特征

## API 接口

### 1. 健康检查

#### GET /health

健康检查

响应：

```json
{
  "status": "healthy",
  "model_loaded": true
}
```

#### GET /model/info

获取模型信息

响应：

```json
{
  "device": "cpu",
  "model_loaded": true,
  "sparse_field_dims": [100, 50, 10, 10],
  "dense_dim": 10,
  "embedding_dim": 8
}
```

### 2. 排序接口

#### POST /rank

完整版排序请求

```json
{
  "user_id": 1,
  "candidates": [1, 2, 3, 4, 5],
  "user_features": {
    "view_1d": 10,
    "click_1d": 2,
    "cart_1d": 1,
    "buy_1d": 0,
    "view_7d": 50,
    "click_7d": 15,
    "cart_7d": 5,
    "buy_7d": 2,
    "view_30d": 100,
    "last_active_hours": 2,
    "prefer_category": [1, 2, 3],
    "prefer_brand": [1, 2]
  },
  "item_features": {
    "1": {"category_id": 1, "brand_id": 1, "price_bucket": 3, "sales_bucket": 5, "hot_score": 100},
    "2": {"category_id": 2, "brand_id": 2, "price_bucket": 2, "sales_bucket": 3, "hot_score": 50}
  }
}
```

响应：

```json
{
  "user_id": 1,
  "ranked_items": [
    {"item_id": 2, "score": 0.85},
    {"item_id": 1, "score": 0.72},
    {"item_id": 3, "score": 0.65},
    {"item_id": 5, "score": 0.45},
    {"item_id": 4, "score": 0.30}
  ]
}
```

#### POST /rank/simple

简化版排序接口（不需要完整的特征输入）

```json
{
  "user_id": 1,
  "candidates": [1, 2, 3, 4, 5]
}
```

### 3. 训练接口

#### POST /data/generate

生成合成训练数据

```json
{
  "num_samples": 50000,
  "num_users": 10000,
  "num_items": 1000,
  "train_ratio": 0.8
}
```

响应：

```json
{
  "status": "success",
  "train_samples": 40000,
  "val_samples": 10000,
  "positive_ratio": 0.3
}
```

#### GET /data/info

获取当前训练数据信息

响应：

```json
{
  "status": "ready",
  "train_samples": 40000,
  "val_samples": 10000,
  "train_positive_ratio": 0.3,
  "val_positive_ratio": 0.3
}
```

#### POST /train

训练 DeepFM 模型

```json
{
  "epochs": 10,
  "batch_size": 256,
  "save_path": "models/deepfm.pt"
}
```

响应：

```json
{
  "status": "success",
  "message": "模型训练完成，已保存到 models/deepfm.pt",
  "metrics": {
    "loss": 0.45,
    "accuracy": 0.82,
    "precision": 0.78,
    "recall": 0.75,
    "f1": 0.76
  }
}
```

## 特征设计

### 用户特征

| 特征名 | 描述 | 类型 |
|--------|------|------|
| view_1d | 近1天浏览数 | int |
| click_1d | 近1天点击数 | int |
| cart_1d | 近1天加购数 | int |
| buy_1d | 近1天购买数 | int |
| view_7d | 近7天浏览数 | int |
| last_active_hours | 最后活跃时间（小时） | int |
| prefer_category | 偏好类目列表 | List[int] |
| prefer_brand | 偏好品牌列表 | List[int] |

### 商品特征

| 特征名 | 描述 | 类型 |
|--------|------|------|
| category_id | 类目ID | int |
| brand_id | 品牌ID | int |
| price_bucket | 价格分桶 | int |
| sales_bucket | 销量分桶 | int |
| hot_score | 热度分数 | float |

### 交叉特征

| 特征名 | 描述 |
|--------|------|
| category_match | 用户偏好类目与商品类目是否匹配 |
| brand_match | 用户偏好品牌与商品品牌是否匹配 |

## 训练流程

1. 启动服务：`uvicorn app.main:app --host 0.0.0.0 --port 8010`
2. 生成训练数据：`POST /data/generate`
3. 训练模型：`POST /train`
4. 使用模型排序：`POST /rank`
