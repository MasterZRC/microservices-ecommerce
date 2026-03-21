# 推荐模块迭代记录（2026-03-14）

## 本轮目标

在现有 Item-CF 基础上，补齐“可直接被前端消费”的推荐结果接口，并提升热门召回质量。

## 本轮变更

1. 推荐服务新增商品详情推荐接口

- `GET /api/recommendation/popular/products?limit=10`
- `GET /api/recommendation/personal/products?userId=1&limit=10`

返回结构统一为：

```json
{
  "products": [
    { "id": 1, "name": "...", "price": 8999, "imageUrl": "..." }
  ]
}
```

2. 行为权重聚合热门召回

- `view=1`
- `click=2`
- `cart=4`
- `favorite=5`
- `buy=8`

热门商品不再仅按简单次数排序，而是按行为权重聚合得分排序。

3. 行为写入后局部缓存失效

- 写入行为后会清理：
  - `recommendation:popular:all`
  - `recommendation:personal:{userId}`

4. 前端首页接入新接口

- 首页优先调用 `/recommendation/popular/products`
- 若无返回则回退旧逻辑（`popular IDs + 全量商品过滤`）

## 配置变更

- 推荐服务新增配置：
  - `services.product.url=${PRODUCT_SERVICE_URL:http://localhost:8002}`
- `docker-compose.yml` 为 `recommendation-service` 注入：
  - `PRODUCT_SERVICE_URL=http://product-service:8002`

## 联调结果

- 已通过网关验证：
  - 热门商品详情推荐接口可返回商品列表
  - 行为打点后个性化商品推荐接口可返回结果
- 前端本地构建通过：`npm run build`

## 已知事项

- 本次 `docker compose up -d --build recommendation-service frontend` 过程中，`frontend` 镜像阶段曾因 Docker Hub 网络超时失败（`nginx:alpine` 拉取超时）；该问题不影响推荐服务后端能力。
