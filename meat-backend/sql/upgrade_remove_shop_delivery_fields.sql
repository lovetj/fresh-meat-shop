-- ==============================================================
-- 维琼鲜肉铺 - 移除 shop_info 表中冗余配送字段增量升级脚本
-- 执行库: fresh_meat_shop
-- 说明:
-- 1. 同城配送规则已完全由 shop_delivery_rule 表多阶梯管理
-- 2. 存量兜底：若有店铺未配置阶梯规则，先将其历史字段迁移到 shop_delivery_rule
-- 3. 幂等安全移除 shop_info 中的 max_delivery_distance、min_order_amount、delivery_fee 字段
-- ==============================================================

USE `fresh_meat_shop`;

-- 步骤 1: 存量兜底迁移：若有尚未配置阶梯规则的店铺，将其现有字段转为第一条阶梯规则
SET @has_rule_tbl = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shop_delivery_rule');
SET @has_shop_max = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shop_info' AND COLUMN_NAME = 'max_delivery_distance');

SET @backup_sql = IF(@has_rule_tbl > 0 AND @has_shop_max > 0, 
    'INSERT INTO `shop_delivery_rule` (`id`, `shop_id`, `min_distance`, `max_distance`, `min_order_amount`, `delivery_fee`, `sort_order`)
     SELECT 
         CONCAT(\'dr_auto_\', SUBSTRING(REPLACE(UUID(), \'-\', \'\'), 1, 24)),
         s.id,
         0.00,
         IFNULL(s.max_delivery_distance, 5.00),
         IFNULL(s.min_order_amount, 20.00),
         IFNULL(s.delivery_fee, 3.00),
         1
     FROM `shop_info` s
     WHERE NOT EXISTS (
         SELECT 1 FROM `shop_delivery_rule` r WHERE r.shop_id = s.id
     )',
    'SELECT 1'
);
PREPARE stmt_backup FROM @backup_sql;
EXECUTE stmt_backup;
DEALLOCATE PREPARE stmt_backup;

-- 步骤 2: 幂等移除 max_delivery_distance
SET @exist_max_dist = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shop_info' AND COLUMN_NAME = 'max_delivery_distance');
SET @drop_max_dist_sql = IF(@exist_max_dist > 0, 'ALTER TABLE `shop_info` DROP COLUMN `max_delivery_distance`', 'SELECT 1');
PREPARE stmt_drop1 FROM @drop_max_dist_sql;
EXECUTE stmt_drop1;
DEALLOCATE PREPARE stmt_drop1;

-- 步骤 3: 幂等移除 min_order_amount
SET @exist_min_amt = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shop_info' AND COLUMN_NAME = 'min_order_amount');
SET @drop_min_amt_sql = IF(@exist_min_amt > 0, 'ALTER TABLE `shop_info` DROP COLUMN `min_order_amount`', 'SELECT 1');
PREPARE stmt_drop2 FROM @drop_min_amt_sql;
EXECUTE stmt_drop2;
DEALLOCATE PREPARE stmt_drop2;

-- 步骤 4: 幂等移除 delivery_fee
SET @exist_fee = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shop_info' AND COLUMN_NAME = 'delivery_fee');
SET @drop_fee_sql = IF(@exist_fee > 0, 'ALTER TABLE `shop_info` DROP COLUMN `delivery_fee`', 'SELECT 1');
PREPARE stmt_drop3 FROM @drop_fee_sql;
EXECUTE stmt_drop3;
DEALLOCATE PREPARE stmt_drop3;

-- 步骤 5: 查看当前 shop_info 表结构验证
DESCRIBE `shop_info`;
