# 命名规范文档 + SQL 约束规范（索引/唯一键命名）

版本：v1.0  
适用项目：microservices-ecommerce  
适用范围：后端微服务、MySQL 数据库、Redis 缓存键、API 路径

---

## 1. 目标与原则

1. 统一命名语义，降低联调与排障成本。  
2. 所有名称可读、可检索、可扩展。  
3. 约束命名可直接反映业务含义与表/字段关系。  
4. 命名风格稳定，不随个人习惯变化。

---

## 2. 通用命名规范

### 2.1 统一风格

- 统一使用小写英文与下划线（snake_case）。
- 禁止拼音、中文、空格、特殊字符（除下划线）。
- 缩写需行业通用（id、url、ip、ttl、sku）。
- 名称长度建议不超过 30 字符（索引/约束不超过 64 字符）。

### 2.2 语义要求

- 名词表达实体，动词表达动作。
- 布尔字段使用 is_/has_ 前缀（如 is_deleted、has_stock）。
- 时间字段统一为 *_time（create_time、update_time、expire_time）。

---

## 3. 服务与模块命名

- 微服务名：<domain>-service  
  例：user-service、product-service、order-service。
- Java 包名：com.ecommerce.<domain>
- 类名：大驼峰（UserService、ProductController）。
- 方法名：小驼峰（createOrder、reduceStock）。

---

## 4. API 命名规范

- 基础路径：/api/<domain>
- 资源路径使用名词：/api/product/list、/api/order/create
- 资源 ID 使用路径参数：/api/product/{id}
- 动作型接口保持动词后缀：/stock/reduce、/stock/increase
- Query 参数统一小写驼峰：pageSize、categoryId

---

## 5. MySQL 表与字段命名规范

### 5.1 表命名

- 使用业务名词，不加 t_ 前缀。
- 单表名优先单数语义（user、product、category）。
- 关系/明细表使用主名 + 后缀（order_item、user_behavior）。

### 5.2 字段命名

- 主键统一 id（BIGINT 自增或雪花）。
- 外键字段统一 <ref>_id（user_id、product_id、order_id）。
- 状态字段统一 status（建议 0/1 或枚举值，并写注释）。
- 金额字段统一 *_amount 或 price（DECIMAL）。
- 数量字段统一 *_count 或 quantity/stock。

### 5.3 审计字段

建议每张业务表至少包含：

- create_time DATETIME
- update_time DATETIME

可选：

- is_deleted TINYINT（逻辑删除）
- version INT（乐观锁）

---

## 6. SQL 约束与索引命名规范

### 6.1 主键

- 主键字段统一 id。
- 主键约束名可省略（使用数据库默认 PRIMARY）。
- 如需显式命名：pk_<table>

### 6.2 唯一键（UNIQUE）

命名格式：

- uk_<table>__<col1>
- uk_<table>__<col1>_<col2>

示例：

- uk_user__username
- uk_cart__user_id_product_id
- uk_order_info__order_no

### 6.3 普通索引（INDEX）

命名格式：

- idx_<table>__<col1>
- idx_<table>__<col1>_<col2>

示例：

- idx_order_info__user_id
- idx_product__category_id
- idx_user_behavior__user_id_create_time

### 6.4 外键（可选）

命名格式：

- fk_<table>__<ref_table>__<col>

示例：

- fk_order_item__order_info__order_id

说明：

- 当前项目为高并发场景，在线链路可按需不启用强外键，优先应用层保证一致性。
- 若启用外键，需在 DDL 与迁移文档中明确级联策略。

### 6.5 检查约束（CHECK）

命名格式：

- ck_<table>__<rule>

示例：

- ck_product__stock_non_negative
- ck_order_info__status_range

---

## 7. Redis Key 命名规范

### 7.1 格式

<domain>:<module>:<biz_key>[:<sub_key>]

示例：

- product:detail:3
- recommendation:popular:all
- seckill:stock:1001
- seckill:order:1001:20001

### 7.2 规则

- 统一小写 + 冒号分隔。
- Key 可读，不使用无语义随机前缀。
- 必须定义 TTL 策略（永久键除外）。
- 热点 Key 需标注并监控命中率与过期策略。

---

## 8. 当前项目标准表（统一后）

- user
- category
- product
- cart
- order_info
- order_item
- user_behavior
- seckill_product

说明：

- 历史 t_ 前缀表已纳入迁移范围，不再作为新代码依赖。

---

## 9. DDL 模板（推荐）

```sql
CREATE TABLE IF NOT EXISTS `example_entity` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `biz_no` VARCHAR(64) NOT NULL COMMENT '业务单号',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '状态',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_example_entity__biz_no` (`biz_no`),
  KEY `idx_example_entity__user_id` (`user_id`),
  KEY `idx_example_entity__status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='示例表';
```

---

## 10. 变更与评审流程

1. 新增表/字段/索引必须提交 DDL 评审。  
2. 命名不符合本规范的 PR 不合并。  
3. 生产变更必须附回滚 SQL。  
4. 所有迁移脚本按版本号管理（如 V20260314_01__unify_table_names.sql）。

---

## 11. 反例（禁止）

- 同一项目混用 t_order 与 order_info。
- 索引名使用无语义名称（idx1、index_abc）。
- 字段名含糊（data1、flag、remark2）。
- Redis Key 无前缀分层（直接用 12345）。

---

## 12. 快速检查清单

- [ ] 表名是否无 t_ 前缀且语义清晰  
- [ ] 主外键字段是否统一 *_id  
- [ ] 唯一键是否使用 uk_ 前缀  
- [ ] 普通索引是否使用 idx_ 前缀  
- [ ] Redis Key 是否按 domain:module:biz_key 分层  
- [ ] 是否明确 TTL 与缓存一致性策略
