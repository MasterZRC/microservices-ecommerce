-- 商品服务数据库初始化脚本
USE ecommerce;

-- 分类表
CREATE TABLE IF NOT EXISTS `category` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '分类ID',
    `name` VARCHAR(50) NOT NULL COMMENT '分类名称',
    `parent_id` BIGINT DEFAULT 0 COMMENT '父分类ID',
    `level` INT DEFAULT 1 COMMENT '层级',
    `sort` INT DEFAULT 0 COMMENT '排序',
    `status` TINYINT DEFAULT 1 COMMENT '状态: 0-禁用, 1-正常',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_parent_id` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品分类表';

-- 商品表
CREATE TABLE IF NOT EXISTS `product` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '商品ID',
    `name` VARCHAR(200) NOT NULL COMMENT '商品名称',
    `description` TEXT COMMENT '商品描述',
    `price` DECIMAL(10,2) NOT NULL COMMENT '售价',
    `stock` INT DEFAULT 0 COMMENT '库存',
    `image_url` VARCHAR(500) COMMENT '主图URL',
    `category_id` BIGINT COMMENT '分类ID',
    `category_name` VARCHAR(50) COMMENT '分类名称',
    `brand` VARCHAR(50) COMMENT '品牌',
    `original_price` DECIMAL(10,2) COMMENT '原价',
    `sales` INT DEFAULT 0 COMMENT '销量',
    `status` TINYINT DEFAULT 1 COMMENT '状态: 0-下架, 1-上架',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_category_id` (`category_id`),
    KEY `idx_name` (`name`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品表';

-- 插入测试分类
INSERT INTO `category` (`id`, `name`, `parent_id`, `level`, `sort`, `status`) VALUES
(1, '电子产品', 0, 1, 1, 1),
(2, '服装', 0, 1, 2, 1),
(3, '食品', 0, 1, 3, 1),
(4, '手机', 1, 2, 1, 1),
(5, '电脑', 1, 2, 2, 1),
(6, '上衣', 2, 2, 1, 1),
(7, '裤装', 2, 2, 2, 1);

-- 插入测试商品
INSERT INTO `product` (`name`, `description`, `price`, `stock`, `image_url`, `category_id`, `category_name`, `brand`, `original_price`, `sales`, `status`) VALUES
('iPhone 15 Pro', '苹果最新款智能手机，A17 Pro芯片，钛金属设计', 7999.00, 100, 'https://picsum.photos/400/400?random=1', 4, '手机', 'Apple', 8999.00, 500, 1),
('MacBook Pro 14', 'M3 Pro芯片，专业级笔记本电脑', 14999.00, 50, 'https://picsum.photos/400/400?random=2', 5, '电脑', 'Apple', 16999.00, 200, 1),
('华为 Mate 60', '麒麟9000s处理器，昆仑玻璃', 5999.00, 80, 'https://picsum.photos/400/400?random=3', 4, '手机', '华为', 6999.00, 300, 1),
('ThinkPad X1 Carbon', '商务办公笔记本，轻薄便携', 8999.00, 60, 'https://picsum.photos/400/400?random=4', 5, '电脑', '联想', 9999.00, 150, 1),
('纯棉T恤', '100%纯棉材质，舒适透气', 99.00, 500, 'https://picsum.photos/400/400?random=5', 6, '上衣', '优衣库', 149.00, 1000, 1),
('牛仔裤', '经典直筒版型，时尚百搭', 199.00, 300, 'https://picsum.photos/400/400?random=6', 7, '裤装', 'Levis', 299.00, 800, 1),
('有机茶叶', '高山有机茶叶，清香回甘', 168.00, 200, 'https://picsum.photos/400/400?random=7', 3, '食品', '茶马古道', 198.00, 600, 1),
('蓝牙耳机', '主动降噪，长续航', 299.00, 150, 'https://picsum.photos/400/400?random=8', 1, '电子产品', '索尼', 499.00, 400, 1);