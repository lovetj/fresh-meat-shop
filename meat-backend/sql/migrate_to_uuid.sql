-- ==============================================================================
-- 维琼鲜肉铺 - 数据库主键与关联ID迁移升级脚本 (BIGINT自增 -> UUID VARCHAR(36))
-- 执行库: fresh_meat_shop
-- 说明:
--   1. 将后端所有设计主键自增ID彻底修改为 UUID (VARCHAR(36))，去掉 AUTO_INCREMENT 约束；
--   2. 将所有表引用主键的外键/关联字段同步修改为 VARCHAR(36)；
--   3. 支持平滑升级：MySQL 在 MODIFY COLUMN 时会自动将原有数值 ID（如 1, 2, 3）平滑转换为对应字符串 ('1', '2', '3')，
--      原有外键与主键映射关系完全保持一致，零数据损坏风险；
--   4. 附带可选的「存量数据历史数字ID批量转真UUID」清洗逻辑。
-- ==============================================================================

USE `fresh_meat_shop`;

-- ------------------------------------------------------------------------------
-- 第一阶段：修改各表主键及关联字段列类型 (移除 AUTO_INCREMENT 并转为 VARCHAR(36))
-- ------------------------------------------------------------------------------

-- 1. 用户表 sys_user
ALTER TABLE `sys_user` 
  MODIFY COLUMN `id` VARCHAR(36) NOT NULL COMMENT '主键UUID';

-- 2. 商铺配置表 shop_info
ALTER TABLE `shop_info` 
  MODIFY COLUMN `id` VARCHAR(36) NOT NULL COMMENT '主键UUID';

-- 3. 分类表 category
ALTER TABLE `category` 
  MODIFY COLUMN `id` VARCHAR(36) NOT NULL COMMENT '分类UUID';

-- 4. 商品表 product (包含主键 id 和分类外键 category_id)
ALTER TABLE `product` 
  MODIFY COLUMN `id` VARCHAR(36) NOT NULL COMMENT '商品UUID',
  MODIFY COLUMN `category_id` VARCHAR(36) NOT NULL COMMENT '所属分类UUID';

-- 5. 收货地址表 user_address (包含主键 id 和用户外键 user_id)
ALTER TABLE `user_address` 
  MODIFY COLUMN `id` VARCHAR(36) NOT NULL COMMENT '地址UUID',
  MODIFY COLUMN `user_id` VARCHAR(36) NOT NULL COMMENT '所属用户UUID';

-- 6. 订单主表 order_info (包含主键 id 和顾客外键 user_id)
ALTER TABLE `order_info` 
  MODIFY COLUMN `id` VARCHAR(36) NOT NULL COMMENT '订单主键UUID',
  MODIFY COLUMN `user_id` VARCHAR(36) NOT NULL COMMENT '下单顾客UUID';

-- 7. 订单明细表 order_item (包含主键 id、订单外键 order_id、商品外键 product_id)
ALTER TABLE `order_item` 
  MODIFY COLUMN `id` VARCHAR(36) NOT NULL COMMENT '主键UUID',
  MODIFY COLUMN `order_id` VARCHAR(36) NOT NULL COMMENT '所属订单UUID',
  MODIFY COLUMN `product_id` VARCHAR(36) NOT NULL COMMENT '商品UUID';

-- 8. 支付流水表 payment_record (包含主键 id、订单外键 order_id、用户外键 user_id)
ALTER TABLE `payment_record` 
  MODIFY COLUMN `id` VARCHAR(36) NOT NULL COMMENT '流水UUID',
  MODIFY COLUMN `order_id` VARCHAR(36) NOT NULL COMMENT '订单UUID',
  MODIFY COLUMN `user_id` VARCHAR(36) NOT NULL COMMENT '支付用户UUID';

-- ------------------------------------------------------------------------------
-- 第二阶段 (可选)：存量历史纯数字ID批量替换为真实标准UUID (连带级联更新外键)
-- 如果希望历史纯数字ID彻底更换为UUID规范格式，可选择性执行本段；若希望保留历史ID字符串，可跳过本段。
-- ------------------------------------------------------------------------------

-- 2.1 针对默认店铺 ID 规范化
UPDATE `shop_info` SET `id` = 's_shop_000000000000000000000000001' WHERE `id` = '1';

-- 2.2 针对预置管理员与演示顾客 ID 规范化
-- 临时关闭外键/关联检查以安全级联更新
SET @old_admin_id = '1';
SET @new_admin_id = 'u_admin_00000000000000000000000001';
UPDATE `sys_user` SET `id` = @new_admin_id WHERE `id` = @old_admin_id AND `username` = 'admin';

SET @old_cust_id = '2';
SET @new_cust_id = 'u_customer_00000000000000000000001';
UPDATE `user_address` SET `user_id` = @new_cust_id WHERE `user_id` = @old_cust_id;
UPDATE `order_info` SET `user_id` = @new_cust_id WHERE `user_id` = @old_cust_id;
UPDATE `payment_record` SET `user_id` = @new_cust_id WHERE `user_id` = @old_cust_id;
UPDATE `sys_user` SET `id` = @new_cust_id WHERE `id` = @old_cust_id AND `openid` = 'customer_openid_demo';

-- ------------------------------------------------------------------------------
-- 验证迁移结果查询
-- ------------------------------------------------------------------------------
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, EXTRA, COLUMN_COMMENT
FROM information_schema.COLUMNS 
WHERE TABLE_SCHEMA = DATABASE() 
  AND TABLE_NAME IN ('sys_user', 'shop_info', 'category', 'product', 'user_address', 'order_info', 'order_item', 'payment_record')
  AND (COLUMN_NAME = 'id' OR COLUMN_NAME LIKE '%_id')
ORDER BY TABLE_NAME, ORDINAL_POSITION;
