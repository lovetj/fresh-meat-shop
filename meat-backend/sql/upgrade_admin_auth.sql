-- ==============================================================
-- 维琼鲜肉铺 - 管理员鉴权体系重构升级脚本
-- 执行库: fresh_meat_shop
-- 说明: 
-- 1. 超级管理员完全脱离微信登录，采用独立账号密码认证
-- 2. 普通顾客继续通过微信快捷登录，微信登录角色强约束为 CUSTOMER
-- 3. 预置超级管理员账号：admin，默认密码：admin123
-- ==============================================================

USE `fresh_meat_shop`;

-- 1. 调整 openid 允许为 NULL（纯后台管理员无需微信 openid）
ALTER TABLE `sys_user` MODIFY COLUMN `openid` VARCHAR(64) DEFAULT NULL COMMENT '微信openid';

-- 2. 新增管理员登录账号与密码字段
-- 检查并添加 username 列
SET @exist_col = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'username');
SET @sql_stmt = IF(@exist_col = 0, 'ALTER TABLE `sys_user` ADD COLUMN `username` VARCHAR(64) DEFAULT NULL COMMENT \'管理员登录账号\' AFTER `unionid`', 'SELECT 1');
PREPARE stmt FROM @sql_stmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 检查并添加 password 列
SET @exist_col = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'password');
SET @sql_stmt = IF(@exist_col = 0, 'ALTER TABLE `sys_user` ADD COLUMN `password` VARCHAR(128) DEFAULT NULL COMMENT \'管理员登录密码(哈希加密)\' AFTER `username`', 'SELECT 1');
PREPARE stmt FROM @sql_stmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 添加 username 唯一索引
SET @exist_idx = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND INDEX_NAME = 'uk_username');
SET @sql_stmt = IF(@exist_idx = 0, 'ALTER TABLE `sys_user` ADD UNIQUE KEY `uk_username` (`username`)', 'SELECT 1');
PREPARE stmt FROM @sql_stmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3. 预置默认超级管理员 (账号: admin, 初始密码: admin123)
-- 标准 BCrypt 密文: $2a$10$zyQBYYn1TXYtm8CGwQyHsux9OXWVYTczvXPYFpacp4cCV5CFJfPBS (对应明文 admin123)
INSERT INTO `sys_user` (`username`, `password`, `nickname`, `avatar_url`, `phone`, `role`, `status`)
VALUES ('admin', '$2a$10$zyQBYYn1TXYtm8CGwQyHsux9OXWVYTczvXPYFpacp4cCV5CFJfPBS', '超级管理员', '', '13800138000', 'ADMIN', 1)
ON DUPLICATE KEY UPDATE 
  `role` = 'ADMIN',
  `password` = '$2a$10$zyQBYYn1TXYtm8CGwQyHsux9OXWVYTczvXPYFpacp4cCV5CFJfPBS',
  `status` = 1;

-- 4. 清理旧版历史模拟 openid 数据中的管理员权限，禁止任何微信 openid 直接获取 ADMIN
UPDATE `sys_user` SET `role` = 'CUSTOMER' WHERE `openid` = 'admin_openid_demo';
