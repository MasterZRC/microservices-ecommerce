-- 用户画像表 - MySQL持久化存储
-- 用于支持用户RFM分层、偏好类目/品牌等维度标签
USE ecommerce;

CREATE TABLE IF NOT EXISTS `user_portrait` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `active_level` VARCHAR(20) COMMENT '活跃等级：高活/中活/低活/沉默',
    `purchase_power` VARCHAR(20) COMMENT '消费能力：高消费/中消费/低消费',
    `prefer_category_ids` VARCHAR(100) COMMENT '偏好类目ID（逗号分隔）',
    `prefer_category_names` VARCHAR(200) COMMENT '偏好类目名称（逗号分隔）',
    `prefer_brands` VARCHAR(200) COMMENT '偏好品牌Top3',
    `price_range` VARCHAR(20) COMMENT '价格偏好：low/middle/high',
    `browse_depth` VARCHAR(20) COMMENT '浏览深度：浅度/中度/深度',
    `rfm_score` DOUBLE COMMENT 'RFM综合得分',
    `last_active_time` DATETIME COMMENT '最近活跃时间',
    `behavior_count` INT DEFAULT 0 COMMENT '行为总数（近30天）',
    `buy_count` INT DEFAULT 0 COMMENT '购买次数（近30天）',
    `cart_count` INT DEFAULT 0 COMMENT '加购次数（近30天）',
    `version` INT DEFAULT 0 COMMENT '版本号（乐观锁）',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_user_id` (`user_id`),
    INDEX `idx_update_time` (`update_time`),
    INDEX `idx_active_level` (`active_level`),
    INDEX `idx_purchase_power` (`purchase_power`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户画像表';
