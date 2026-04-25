# 接口清单

> 初版来自全量功能测试阶段的追踪表，现作为**长期参考**保留在仓库中。  
> 状态列原为手测勾选，未随代码自动更新；以各服务 OpenAPI/Controller 为准。  
> 状态：[ ] 未测  [x] 通过  [!] 失败  [F] 已修复

---

## 一、用户服务 user-service（基础路径 `/api/user`）

| # | 方法 | 路径 | 鉴权 | 状态 | 备注 |
|---|------|------|------|------|------|
| 1 | POST | `/api/user/register` | 否 | [ ] | 注册成功 / 用户名重复 |
| 2 | POST | `/api/user/login` | 否 | [ ] | 正确密码 / 错误密码 |
| 3 | GET  | `/api/user/{id}` | 是 | [ ] | 已存在 / 不存在 |
| 4 | GET  | `/api/user/check/{username}` | 是 | [ ] |  |
| 5 | PUT  | `/api/user/update` | 是 | [ ] |  |

## 二、商品服务 product-service（基础路径 `/api/product`）

| # | 方法 | 路径 | 鉴权 | 状态 | 备注 |
|---|------|------|------|------|------|
| 1 | GET  | `/api/product/list` | 否 | [ ] | 分页 + 关键词 + categoryId |
| 2 | GET  | `/api/product/{id}` | 是 | [ ] |  |
| 3 | POST | `/api/product/create` | 是 | [ ] |  |
| 4 | PUT  | `/api/product/update` | 是 | [ ] |  |
| 5 | DELETE | `/api/product/{id}` | 是 | [ ] |  |
| 6 | GET  | `/api/product/category/list` | 是 | [ ] |  |
| 7 | POST | `/api/product/stock/reduce` | 是 | [ ] |  |
| 8 | POST | `/api/product/stock/increase` | 是 | [ ] |  |
| 9 | POST | `/api/product/batch` | 是 | [ ] | body=List\<Long\> |
| 10 | GET | `/api/product/batch?ids=...` | 是 | [ ] |  |

## 三、订单服务 order-service（基础路径 `/api/order`）

| # | 方法 | 路径 | 鉴权 | 状态 | 备注 |
|---|------|------|------|------|------|
| 1 | POST | `/api/order/create` | 是 | [ ] | OrderCreateRequest |
| 2 | POST | `/api/order/create/seckill` | 是 | [ ] | SeckillOrderCreateRequest |
| 3 | GET  | `/api/order/list?userId=` | 是 | [ ] |  |
| 4 | GET  | `/api/order/{id}` | 是 | [ ] |  |
| 5 | POST | `/api/order/pay?orderId=&userId=` | 是 | [ ] |  |
| 6 | POST | `/api/order/cart/add` | 是 | [ ] | body 或 query |
| 7 | GET  | `/api/order/cart/list?userId=` | 是 | [ ] |  |
| 8 | GET  | `/api/order/cart/count?userId=` | 是 | [ ] |  |
| 9 | DELETE | `/api/order/cart/remove?userId=&productId=` | 是 | [ ] |  |
| 10 | DELETE | `/api/order/cart/clear?userId=` | 是 | [ ] |  |

## 四、推荐服务 recommendation-service（基础路径 `/api/recommendation`）

### 4.1 推荐主流程
| # | 方法 | 路径 | 鉴权 | 状态 |
|---|------|------|------|------|
| 1 | GET | `/api/recommendation/personal?userId=&limit=` | 否 | [ ] |
| 2 | GET | `/api/recommendation/personal/products?userId=&limit=` | 否 | [ ] |
| 3 | GET | `/api/recommendation/popular?limit=` | 否 | [ ] |
| 4 | GET | `/api/recommendation/popular/products?limit=` | 否 | [ ] |

### 4.2 行为/曝光
| # | 方法 | 路径 | 鉴权 | 状态 |
|---|------|------|------|------|
| 5 | POST | `/api/recommendation/behavior` | 是 | [ ] |
| 6 | POST | `/api/recommendation/exposure` | 是 | [ ] |
| 7 | POST | `/api/recommendation/exposure/batch` | 是 | [ ] |
| 8 | GET  | `/api/recommendation/exposure/samples?userId=&limit=` | 是 | [ ] |

### 4.3 评测/缓存
| # | 方法 | 路径 | 鉴权 | 状态 |
|---|------|------|------|------|
| 9 | POST | `/api/recommendation/refresh` | 是 | [ ] |
| 10 | GET | `/api/recommendation/baseline/compare?topK=&sampleUsers=` | 是 | [ ] |

### 4.4 灰度
| # | 方法 | 路径 | 鉴权 | 状态 |
|---|------|------|------|------|
| 11 | GET | `/api/recommendation/gray/status` | 否（可读） | [ ] |
| 12 | GET | `/api/recommendation/gray/check?userId=` | 否（可读） | [ ] |
| 13 | GET | `/api/recommendation/gray/metrics?date=` | 是 | [ ] |
| 14 | GET | `/api/recommendation/gray/compare?date=` | 是 | [ ] |
| 15 | POST | `/api/recommendation/gray/click` | 是 | [ ] |
| 16 | POST | `/api/recommendation/gray/cart` | 是 | [ ] |
| 17 | POST | `/api/recommendation/gray/order` | 是 | [ ] |

### 4.5 A/B 实验
| # | 方法 | 路径 | 鉴权 | 状态 |
|---|------|------|------|------|
| 18 | POST | `/api/recommendation/experiment/create` | 是 | [ ] |
| 19 | GET  | `/api/recommendation/experiment/list` | 是 | [ ] |
| 20 | GET  | `/api/recommendation/experiment/{id}` | 是 | [ ] |
| 21 | GET  | `/api/recommendation/experiment/{id}/stats` | 是 | [ ] |
| 22 | GET  | `/api/recommendation/experiment/user/{userId}` | 是 | [ ] |
| 23 | POST | `/api/recommendation/experiment/{id}/end` | 是 | [ ] |
| 24 | DELETE | `/api/recommendation/experiment/{id}` | 是 | [ ] |

### 4.6 用户画像
| # | 方法 | 路径 | 鉴权 | 状态 |
|---|------|------|------|------|
| 25 | GET  | `/api/recommendation/profile/{userId}` | 是 | [ ] |
| 26 | POST | `/api/recommendation/profile/{userId}/refresh` | 是 | [ ] |

## 五、秒杀服务 seckill-service

### 5.1 普通秒杀（基础路径 `/api/seckill`）
| # | 方法 | 路径 | 鉴权 | 状态 |
|---|------|------|------|------|
| 1 | GET  | `/api/seckill/products` | 否 | [ ] |
| 2 | GET  | `/api/seckill/products/upcoming?limit=` | 否 | [ ] |
| 3 | GET  | `/api/seckill/activity` | 否 | [ ] |
| 4 | POST | `/api/seckill/start?userId=&seckillProductId=&quantity=` | 是 | [ ] |
| 5 | GET  | `/api/seckill/stock?seckillProductId=` | 是 | [ ] |
| 6 | GET  | `/api/seckill/queue/size` | 是 | [ ] |
| 7 | GET  | `/api/seckill/queue/metrics` | 是 | [ ] |
| 8 | POST | `/api/seckill/init?seckillProductId=&stock=` | 是 | [ ] |
| 9 | DELETE | `/api/seckill/cache` | 是 | [ ] |
| 10 | GET | `/api/seckill/health` | 是 | [ ] |

### 5.2 秒杀管理（基础路径 `/api/admin/seckill` 在 seckill-service 中）
> 说明：admin-service 也定义了 `/api/admin/seckill`，路由通过 Gateway 时会优先匹配 admin-service。该 controller 仅供 admin-service 内部调用。

| # | 方法 | 路径 | 状态 |
|---|------|------|------|
| 11 | POST | `/api/admin/seckill/product` | [ ] |
| 12 | PUT  | `/api/admin/seckill/product/{id}` | [ ] |
| 13 | DELETE | `/api/admin/seckill/product/{id}` | [ ] |
| 14 | GET  | `/api/admin/seckill/product/by-activity/{activityId}` | [ ] |
| 15 | PUT  | `/api/admin/seckill/stock/{id}?stock=` | [ ] |

## 六、Admin 服务 admin-service

### 6.1 鉴权（`/api/admin/auth`）
| # | 方法 | 路径 | 鉴权 | 状态 |
|---|------|------|------|------|
| 1 | POST | `/api/admin/auth/login` | 否 | [ ] |
| 2 | POST | `/api/admin/auth/logout` | 是 | [ ] |
| 3 | GET  | `/api/admin/auth/info` | 是 | [ ] |
| 4 | PUT  | `/api/admin/auth/password` | 是 | [ ] |

### 6.2 仪表盘（`/api/admin/dashboard`）
| # | 方法 | 路径 | 鉴权 | 状态 |
|---|------|------|------|------|
| 5 | GET | `/api/admin/dashboard/stats` | 是 | [ ] |
| 6 | GET | `/api/admin/dashboard/recent-orders?limit=` | 是 | [ ] |

### 6.3 商品管理（`/api/admin/products`）
| # | 方法 | 路径 | 鉴权 | 状态 |
|---|------|------|------|------|
| 7 | GET  | `/api/admin/products?page=&size=` | 是 | [ ] |
| 8 | GET  | `/api/admin/products/{id}` | 是 | [ ] |
| 9 | POST | `/api/admin/products` | 是 | [ ] |
| 10 | PUT | `/api/admin/products/{id}` | 是 | [ ] |
| 11 | DELETE | `/api/admin/products/{id}` | 是 | [ ] |
| 12 | PUT | `/api/admin/products/{id}/stock?stock=` | 是 | [ ] |
| 13 | GET | `/api/admin/products/categories` | 是 | [ ] |

### 6.4 订单管理（`/api/admin/orders`）
| # | 方法 | 路径 | 鉴权 | 状态 |
|---|------|------|------|------|
| 14 | GET | `/api/admin/orders?page=&size=` | 是 | [ ] |
| 15 | GET | `/api/admin/orders/{id}` | 是 | [ ] |
| 16 | PUT | `/api/admin/orders/{id}/status` | 是 | [ ] |
| 17 | GET | `/api/admin/orders/stats` | 是 | [ ] |

### 6.5 秒杀管理（`/api/admin/seckill`）
| # | 方法 | 路径 | 鉴权 | 状态 |
|---|------|------|------|------|
| 18 | GET  | `/api/admin/seckill/activities?page=&size=` | 是 | [ ] |
| 19 | GET  | `/api/admin/seckill/activities/{id}` | 是 | [ ] |
| 20 | POST | `/api/admin/seckill/activities` | 是 | [ ] |
| 21 | PUT  | `/api/admin/seckill/activities/{id}` | 是 | [ ] |
| 22 | DELETE | `/api/admin/seckill/activities/{id}` | 是 | [ ] |
| 23 | PUT  | `/api/admin/seckill/activities/{id}/stock?stock=` | 是 | [ ] |

### 6.6 告警（`/api/alert`）
| # | 方法 | 路径 | 鉴权 | 状态 |
|---|------|------|------|------|
| 24 | POST | `/api/alert/webhook` | 否 | [ ] |
| 25 | GET  | `/api/alert/active` | 是 | [ ] |
| 26 | GET  | `/api/alert/history?limit=` | 是 | [ ] |

## 七、API 网关 api-gateway

| # | 方法 | 路径 | 鉴权 | 状态 |
|---|------|------|------|------|
| 1 | GET | `/` 或 `/api` | 否 | [ ] |
| 2 | GET | `/actuator/health` | 否 | [ ] |
| 3 | GET | `/actuator/prometheus` | 否 | [ ] |

> 网关其余职责通过路由测试覆盖：JWT 注入、白名单（excluded）、限流、404 透传。

## 八、Python 排序服务 recommendation-rank-service（端口 8010）

| # | 方法 | 路径 | 鉴权 | 状态 |
|---|------|------|------|------|
| 1 | GET  | `/health` | 否 | [ ] |
| 2 | GET  | `/model/info` | 否 | [ ] |
| 3 | POST | `/rank` | 可选 | [ ] |
| 4 | POST | `/rank/simple` | 可选 | [ ] |
| 5 | POST | `/rank/attention` | 可选 | [ ] |
| 6 | POST | `/data/generate` | 否 | [ ] |
| 7 | POST | `/data/load` | 否 | [ ] |
| 8 | GET  | `/data/info` | 否 | [ ] |
| 9 | POST | `/train` | 否 | [ ] |
| 10 | POST | `/model/incremental-update` | 可选 | [ ] |
| 11 | POST | `/evaluate/online` | 可选 | [ ] |
| 12 | POST | `/evaluate/compare` | 可选 | [ ] |
| 13 | POST | `/online/exposure` | 可选 | [ ] |
| 14 | POST | `/online/click` | 可选 | [ ] |
| 15 | GET  | `/online/status` | 可选 | [ ] |
| 16 | POST | `/online/stop` | 可选 | [ ] |
| 17 | POST | `/online/start` | 可选 | [ ] |
| 18 | POST | `/train/exposure-negative` | 可选 | [ ] |

---

## 接口总数汇总

| 模块 | 接口数 |
|------|--------|
| 用户服务 | 5 |
| 商品服务 | 10 |
| 订单服务 | 10 |
| 推荐服务 | 26 |
| 秒杀服务（普通+管理） | 15 |
| Admin 服务 | 26 |
| 网关 | 3 |
| Python rank | 18 |
| **合计** | **113** |
