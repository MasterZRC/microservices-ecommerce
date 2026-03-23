-- 秒杀本地消息表 - 保证消息不丢失
USE ecommerce;

CREATE TABLE IF NOT EXISTS `seckill_local_message` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `message_id` VARCHAR(100) NOT NULL COMMENT '消息ID（对应Redis Stream的RecordId）',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `seckill_product_id` BIGINT NOT NULL COMMENT '秒杀商品ID',
    `quantity` INT DEFAULT 1 COMMENT '购买数量',
    `status` VARCHAR(20) DEFAULT 'pending' COMMENT '消息状态：pending/confirmed/failed',
    `retry_count` INT DEFAULT 0 COMMENT '重试次数',
    `error_message` TEXT COMMENT '错误消息',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `confirm_time` DATETIME COMMENT '确认时间',
    INDEX `idx_status_create_time` (`status`, `create_time`),
    INDEX `idx_message_id` (`message_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀本地消息表';
