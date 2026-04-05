-- ================================================
-- A. 管理员账号表 (admin_user)
-- ================================================
CREATE TABLE IF NOT EXISTS `admin_user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(50) NOT NULL COMMENT '用户名',
  `password` varchar(255) NOT NULL COMMENT '密码(BCrypt加密)',
  `nickname` varchar(50) DEFAULT NULL COMMENT '昵称',
  `email` varchar(100) DEFAULT NULL COMMENT '邮箱',
  `phone` varchar(20) DEFAULT NULL COMMENT '手机号',
  `avatar` varchar(255) DEFAULT NULL COMMENT '头像',
  `role_id` bigint DEFAULT NULL COMMENT '关联角色ID',
  `status` tinyint DEFAULT 1 COMMENT '状态: 0-禁用, 1-正常',
  `last_login_time` datetime DEFAULT NULL COMMENT '最后登录时间',
  `last_login_ip` varchar(50) DEFAULT NULL COMMENT '最后登录IP',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 默认超级管理员: admin / admin123
INSERT INTO `admin_user` (`id`, `username`, `password`, `nickname`, `role_id`, `status`, `create_time`, `deleted`) VALUES
(1, 'admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '超级管理员', 1, 1, NOW(), 0)
ON DUPLICATE KEY UPDATE `id`=`id`;

-- ================================================
-- B. 管理员角色表 (admin_role)
-- ================================================
CREATE TABLE IF NOT EXISTS `admin_role` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `code` varchar(50) NOT NULL COMMENT '角色代码: super_admin/admin/operator',
  `name` varchar(50) NOT NULL COMMENT '角色名称',
  `description` varchar(255) DEFAULT NULL COMMENT '角色描述',
  `permissions` text COMMENT '权限列表(JSON数组)',
  `status` tinyint DEFAULT 1 COMMENT '状态',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `admin_role` (`id`, `code`, `name`, `description`, `permissions`, `status`, `create_time`) VALUES
(1, 'super_admin', '超级管理员', '拥有所有权限', '["*"]', 1, NOW()),
(2, 'admin', '管理员', '拥有日常运营权限', '["product:*", "order:*", "seckill:*", "stats:view", "stats:export"]', 1, NOW()),
(3, 'operator', '运营人员', '查看和管理运营数据', '["product:view", "product:edit", "order:view", "order:edit", "seckill:view", "stats:view"]', 1, NOW())
ON DUPLICATE KEY UPDATE `id`=`id`;

-- ================================================
-- C. 管理员操作日志表 (admin_operation_log)
-- ================================================
CREATE TABLE IF NOT EXISTS `admin_operation_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `admin_id` bigint DEFAULT NULL COMMENT '管理员ID',
  `username` varchar(50) DEFAULT NULL COMMENT '管理员用户名',
  `module` varchar(50) DEFAULT NULL COMMENT '操作模块',
  `operation` varchar(50) DEFAULT NULL COMMENT '操作类型',
  `method` varchar(100) DEFAULT NULL COMMENT 'HTTP方法',
  `url` varchar(255) DEFAULT NULL COMMENT '请求URL',
  `params` text COMMENT '请求参数(JSON)',
  `result` text COMMENT '返回结果(JSON)',
  `ip` varchar(50) DEFAULT NULL COMMENT 'IP地址',
  `user_agent` varchar(500) DEFAULT NULL,
  `status` tinyint DEFAULT 1 COMMENT '操作状态: 1-成功, 0-失败',
  `error_message` text COMMENT '错误信息',
  `duration` bigint DEFAULT NULL COMMENT '执行时长(ms)',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_admin_id` (`admin_id`),
  KEY `idx_module` (`module`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
