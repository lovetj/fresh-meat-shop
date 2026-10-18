-- ==============================================================
-- 维琼鲜肉铺 - 移除 order_info 表掌柜内部备忘字段增量升级脚本
-- 执行库: fresh_meat_shop
-- 说明:
-- 1. 掌柜内部备忘功能已整体下线（前端入口、后端接口同步移除）
-- 2. 幂等安全移除 order_info 中的 merchant_remark 字段
-- ==============================================================

USE `fresh_meat_shop`;

-- 步骤 1: 幂等移除 merchant_remark
SET @exist_col = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order_info' AND COLUMN_NAME = 'merchant_remark');
SET @drop_col_sql = IF(@exist_col > 0, 'ALTER TABLE `order_info` DROP COLUMN `merchant_remark`', 'SELECT 1');
PREPARE stmt_drop FROM @drop_col_sql;
EXECUTE stmt_drop;
DEALLOCATE PREPARE stmt_drop;

-- 步骤 2: 查看当前 order_info 表结构验证
DESCRIBE `order_info`;
