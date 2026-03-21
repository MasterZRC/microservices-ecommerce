-- 秒杀服务数据库初始化脚本
USE ecommerce;

-- 秒杀商品表
CREATE TABLE IF NOT EXISTS `seckill_product` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '秒杀商品ID',
    `product_id` BIGINT NOT NULL COMMENT '商品ID',
    `product_name` VARCHAR(200) NOT NULL COMMENT '商品名称',
    `product_image` VARCHAR(500) COMMENT '商品图片',
    `seckill_price` DECIMAL(10,2) NOT NULL COMMENT '秒杀价',
    `total_stock` INT NOT NULL COMMENT '总库存',
    `available_stock` INT NOT NULL COMMENT '可用库存',
    `start_time` DATETIME NOT NULL COMMENT '开始时间',
    `end_time` DATETIME NOT NULL COMMENT '结束时间',
    `status` TINYINT DEFAULT 1 COMMENT '状态: 0-禁用, 1-启用',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_product_id` (`product_id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀商品表';

-- 插入测试秒杀商品（设置为当前时间有效的活动）
-- 使用当前日期作为活动日期
INSERT INTO `seckill_product` (`product_id`, `product_name`, `product_image`, `seckill_price`, `total_stock`, `available_stock`, `start_time`, `end_time`, `status`) VALUES
(1, 'iPhone 15 Pro 秒杀', 'https://picsum.photos/400/400?random=1', 6999.00, 10, 10, DATE_FORMAT(NOW(), '%Y-%m-%d 10:00:00'), DATE_FORMAT(NOW(), '%Y-%m-%d 22:00:00'), 1),
(3, '华为 Mate 60 秒杀', 'https://picsum.photos/400/400?random=3', 4999.00, 20, 20, DATE_FORMAT(NOW(), '%Y-%m-%d 14:00:00'), DATE_FORMAT(NOW(), '%Y-%m-%d 16:00:00'), 1),
(2, 'MacBook Pro 14寸', 'https://picsum.photos/400/400?random=2', 8999.00, 15, 15, DATE_FORMAT(NOW(), '%Y-%m-%d 18:00:00'), DATE_FORMAT(NOW(), '%Y-%m-%d 21:00:00'), 1);