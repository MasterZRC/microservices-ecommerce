-- 推荐服务数据库初始化脚本
USE ecommerce;

-- 用户行为表
CREATE TABLE IF NOT EXISTS `user_behavior` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `product_id` BIGINT NOT NULL COMMENT '商品ID',
    `behavior_type` VARCHAR(20) NOT NULL COMMENT '行为类型: view, click, cart, buy, favorite',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_product_id` (`product_id`),
    KEY `idx_behavior_type` (`behavior_type`),
    -- 复合索引：加速用户-商品相似度计算及行为查询
    KEY `idx_user_product_behavior_time` (`user_id`, `product_id`, `behavior_type`, `create_time`),
    -- 复合索引：加速按用户ID和时间范围查询行为
    KEY `idx_user_time` (`user_id`, `create_time`),
    -- 复合索引：加速按商品和时间范围查询
    KEY `idx_product_time` (`product_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户行为表';

-- 插入模拟行为数据
INSERT INTO `user_behavior` (`user_id`, `product_id`, `behavior_type`) VALUES
(1, 1, 'view'), (1, 1, 'click'), (1, 2, 'buy'),
(2, 1, 'view'), (2, 2, 'click'), (2, 3, 'buy'),
(3, 2, 'view'), (3, 3, 'click'), (3, 4, 'buy'),
(4, 1, 'view'), (4, 5, 'click'),
(5, 3, 'view'), (5, 6, 'click');