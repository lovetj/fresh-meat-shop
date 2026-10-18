-- ==============================================================
-- 维琼鲜肉铺 - 掌柜订单处理与自提核销增强升级脚本
-- 执行库: fresh_meat_shop
-- 说明:
-- 1. order_info 表增加 pickup_code (自提核销提货码)
-- 2. order_info 表增加 original_pay_amount (称重改价前原始金额)
-- 3. 创建 idx_pickup_code 索引加快核销查询
-- 4. 为历史自取订单自动补充 6 位随机核销提货码
-- ==============================================================

USE `fresh_meat_shop`;

-- 1. order_info 表增加 pickup_code
SET @exist_col = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order_info' AND COLUMN_NAME = 'pickup_code');
SET @sql_stmt = IF(@exist_col = 0, 'ALTER TABLE `order_info` ADD COLUMN `pickup_code` VARCHAR(16) DEFAULT NULL COMMENT \'自提核销码(自取订单生成的提货凭据)\' AFTER `pay_amount`', 'SELECT 1');
PREPARE stmt FROM @sql_stmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. order_info 表增加 original_pay_amount
SET @exist_col = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order_info' AND COLUMN_NAME = 'original_pay_amount');
SET @sql_stmt = IF(@exist_col = 0, 'ALTER TABLE `order_info` ADD COLUMN `original_pay_amount` DECIMAL(10, 2) DEFAULT NULL COMMENT \'改价前原始应付金额\' AFTER `pay_amount`', 'SELECT 1');
PREPARE stmt FROM @sql_stmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3. 增加 idx_pickup_code 索引
SET @exist_idx = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order_info' AND INDEX_NAME = 'idx_pickup_code');
SET @sql_stmt = IF(@exist_idx = 0, 'ALTER TABLE `order_info` ADD INDEX `idx_pickup_code` (`pickup_code`)', 'SELECT 1');
PREPARE stmt FROM @sql_stmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 4. 为既有的自取订单 (delivery_type = 1 且 pickup_code 为空) 自动补充 6 位数字提货核销码
UPDATE `order_info` 
SET `pickup_code` = LPAD(FLOOR(100000 + (RAND() * 899999)), 6, '0')
WHERE `delivery_type` = 1 AND (`pickup_code` IS NULL OR `pickup_code` = '');
