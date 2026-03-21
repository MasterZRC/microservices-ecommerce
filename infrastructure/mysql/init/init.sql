-- 初始化数据库
SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS ecommerce DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE ecommerce;

-- 用户表
CREATE TABLE IF NOT EXISTS `user` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `username` VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名',
    `password` VARCHAR(255) NOT NULL COMMENT '密码',
    `email` VARCHAR(100) COMMENT '邮箱',
    `phone` VARCHAR(20) COMMENT '手机号',
    `nickname` VARCHAR(50) COMMENT '昵称',
    `avatar` VARCHAR(255) COMMENT '头像URL',
    `status` TINYINT DEFAULT 1 COMMENT '状态 0-禁用 1-正常',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 商品分类表
CREATE TABLE IF NOT EXISTS `category` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `name` VARCHAR(50) NOT NULL COMMENT '分类名称',
    `parent_id` BIGINT DEFAULT 0 COMMENT '父分类ID',
    `level` INT DEFAULT 1 COMMENT '层级',
    `sort` INT DEFAULT 0 COMMENT '排序',
    `status` TINYINT DEFAULT 1 COMMENT '状态',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品分类表';

-- 商品表
CREATE TABLE IF NOT EXISTS `product` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `name` VARCHAR(200) NOT NULL COMMENT '商品名称',
    `description` TEXT COMMENT '商品描述',
    `price` DECIMAL(10,2) NOT NULL COMMENT '价格',
    `original_price` DECIMAL(10,2) COMMENT '原价',
    `stock` INT DEFAULT 0 COMMENT '库存',
    `sales` INT DEFAULT 0 COMMENT '销量',
    `category_id` BIGINT COMMENT '分类ID',
    `category_name` VARCHAR(50) COMMENT '分类名称',
    `brand` VARCHAR(50) COMMENT '品牌',
    `image_url` VARCHAR(500) COMMENT '主图URL',
    `status` TINYINT DEFAULT 1 COMMENT '状态 0-下架 1-上架',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品表';

-- 购物车表
CREATE TABLE IF NOT EXISTS `cart` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `product_id` BIGINT NOT NULL COMMENT '商品ID',
    `product_name` VARCHAR(200) COMMENT '商品名称',
    `product_image` VARCHAR(500) COMMENT '商品图片',
    `quantity` INT NOT NULL DEFAULT 1 COMMENT '数量',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_user_product` (`user_id`, `product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='购物车表';

-- 订单表
CREATE TABLE IF NOT EXISTS `order_info` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `order_no` VARCHAR(50) NOT NULL UNIQUE COMMENT '订单号',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `receiver_name` VARCHAR(50) NOT NULL COMMENT '收货人',
    `receiver_phone` VARCHAR(20) NOT NULL COMMENT '联系电话',
    `receiver_address` VARCHAR(255) NOT NULL COMMENT '收货地址',
    `message_id` VARCHAR(64) UNIQUE COMMENT '异步消息ID(秒杀幂等)',
    `total_amount` DECIMAL(10,2) NOT NULL COMMENT '订单总金额',
    `status` TINYINT DEFAULT 0 COMMENT '状态 0-待支付 1-已支付 2-已发货 3-已完成 4-已取消',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

-- 订单项表
CREATE TABLE IF NOT EXISTS `order_item` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `order_id` BIGINT NOT NULL COMMENT '订单ID',
    `product_id` BIGINT NOT NULL COMMENT '商品ID',
    `product_name` VARCHAR(200) COMMENT '商品名称',
    `product_image` VARCHAR(500) COMMENT '商品图片',
    `price` DECIMAL(10,2) NOT NULL COMMENT '单价',
    `quantity` INT NOT NULL COMMENT '数量',
    `total_price` DECIMAL(10,2) NOT NULL COMMENT '小计'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单项表';

-- 秒杀商品表
CREATE TABLE IF NOT EXISTS `seckill_product` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `product_id` BIGINT NOT NULL COMMENT '商品ID',
    `product_name` VARCHAR(200) COMMENT '商品名称',
    `product_image` VARCHAR(500) COMMENT '商品图片',
    `seckill_price` DECIMAL(10,2) NOT NULL COMMENT '秒杀价',
    `original_price` DECIMAL(10,2) COMMENT '原价',
    `total_stock` INT NOT NULL COMMENT '总库存',
    `available_stock` INT NOT NULL COMMENT '可用库存',
    `start_time` DATETIME NOT NULL COMMENT '开始时间',
    `end_time` DATETIME NOT NULL COMMENT '结束时间',
    `status` TINYINT DEFAULT 1 COMMENT '状态',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀商品表';

-- 用户行为表 (推荐系统)
CREATE TABLE IF NOT EXISTS `user_behavior` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `product_id` BIGINT NOT NULL COMMENT '商品ID',
    `behavior_type` VARCHAR(20) NOT NULL COMMENT '行为类型 view/cart/favorite/purchase',
    `score` DECIMAL(5,2) DEFAULT 1.0 COMMENT '行为得分',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_product (`user_id`, `product_id`),
    INDEX idx_product (`product_id`),
    INDEX idx_user_time (`user_id`, `create_time`),
    -- 复合索引：加速用户-商品相似度计算及行为查询
    INDEX idx_user_product_behavior_time (`user_id`, `product_id`, `behavior_type`, `create_time`),
    -- 复合索引：加速按商品和时间范围查询
    INDEX idx_product_time (`product_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户行为表';

-- 插入测试数据
INSERT INTO `category` (`id`, `name`, `parent_id`, `level`, `sort`) VALUES
(1, '电子产品', 0, 1, 1),
(2, '服装鞋包', 0, 1, 2),
(3, '食品生鲜', 0, 1, 3);

INSERT INTO `product` (`id`, `name`, `description`, `price`, `original_price`, `stock`, `sales`, `category_id`, `category_name`, `brand`, `image_url`) VALUES
(1, 'iPhone 15 Pro', 'Apple iPhone 15 Pro 256GB', 8999.00, 9999.00, 100, 520, 1, '电子产品', 'Apple', 'https://picsum.photos/400/400?random=1'),
(2, '华为 Mate 60', '华为 Mate 60 512GB', 6999.00, 7999.00, 80, 320, 1, '电子产品', '华为', 'https://picsum.photos/400/400?random=2'),
(3, 'MacBook Pro', 'MacBook Pro 14英寸 M3', 16999.00, 18999.00, 50, 180, 1, '电子产品', 'Apple', 'https://picsum.photos/400/400?random=3'),
(4, 'Nike运动鞋', 'Nike Air Max 270', 599.00, 899.00, 200, 890, 2, '服装鞋包', 'Nike', 'https://picsum.photos/400/400?random=4'),
(5, '阿迪达斯T恤', 'Adidas 运动T恤', 199.00, 399.00, 300, 1200, 2, '服装鞋包', 'Adidas', 'https://picsum.photos/400/400?random=5'),
(6, '新鲜三文鱼', '挪威三文鱼切片 500g', 129.00, 159.00, 50, 2300, 3, '食品生鲜', '海鲜', 'https://picsum.photos/400/400?random=6');

INSERT INTO `user` (`id`, `username`, `password`, `email`, `phone`, `nickname`) VALUES
(1, 'test', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5EH', 'test@ecommerce.com', '13800138001', '测试用户');
