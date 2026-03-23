-- Phase 1: 曝光日志表
-- 用于记录用户对商品的曝光行为，支持曝光负采样
USE ecommerce;

-- 商品曝光表
CREATE TABLE IF NOT EXISTS `product_exposure` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `product_id` BIGINT NOT NULL COMMENT '商品ID',
    `position` INT DEFAULT 0 COMMENT '推荐位排名',
    `recommend_type` VARCHAR(20) DEFAULT 'deepfm' COMMENT '推荐来源: deepfm/cf/popular',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '曝光时间',
    PRIMARY KEY (`id`),
    -- 复合索引：按用户+时间查询，用于获取用户历史曝光
    KEY `idx_user_time` (`user_id`, `create_time`),
    -- 复合索引：按商品+时间查询，用于获取商品曝光统计
    KEY `idx_product_time` (`product_id`, `create_time`),
    -- 唯一索引：防止同一用户对同一商品的重复曝光记录（同一推荐周期内）
    UNIQUE KEY `uk_user_product_recommend` (`user_id`, `product_id`, `recommend_type`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品曝光日志表';

-- 用户曝光负采样视图：查询用户曝光但未点击/购买/加购的商品
-- 这些商品是高质量的负样本（用户看到了但没兴趣）
CREATE OR REPLACE VIEW `v_exposure_negative_samples` AS
SELECT
    pe.user_id,
    pe.product_id,
    pe.position,
    pe.recommend_type,
    pe.create_time AS exposure_time,
    ub.id AS has_behavior,
    ub.behavior_type AS user_behavior
FROM product_exposure pe
LEFT JOIN user_behavior ub
    ON ub.user_id = pe.user_id
    AND ub.product_id = pe.product_id
    AND ub.behavior_type IN ('click', 'buy', 'cart', 'favorite')
WHERE pe.create_time >= DATE_SUB(NOW(), INTERVAL 7 DAY)
ORDER BY pe.user_id, pe.create_time DESC;
