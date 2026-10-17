-- ==============================================================
-- 维琼鲜肉铺 - 多店铺与多管理员架构升级脚本
-- 执行库: fresh_meat_shop
-- 说明:
-- 1. 新增 shop_admin 关联表，支持每个店铺配置多个管理员(多对多/店长+店员权限)
-- 2. product 表增加 shop_id，支持商品绑定具体店铺并隔离
-- 3. order_info 表增加 shop_id，支持订单绑定店铺与分店配送核算
-- 4. shop_info 表增加 sort_order 排序字段
-- 5. 自动兼容历史单店数据，并预置多个店铺及协同管理员示范数据
-- ==============================================================

USE `fresh_meat_shop`;

-- 1. shop_info 表增加 sort_order 排序字段
SET @exist_col = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shop_info' AND COLUMN_NAME = 'sort_order');
SET @sql_stmt = IF(@exist_col = 0, 'ALTER TABLE `shop_info` ADD COLUMN `sort_order` INT NOT NULL DEFAULT 0 COMMENT \'排序权重(越大越靠前)\' AFTER `status`', 'SELECT 1');
PREPARE stmt FROM @sql_stmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. 创建店铺与管理员关联表 shop_admin (支持一个店铺拥有多个管理员，支持管理员多店协同)
CREATE TABLE IF NOT EXISTS `shop_admin` (
  `id` VARCHAR(36) NOT NULL COMMENT '主键UUID',
  `shop_id` VARCHAR(36) NOT NULL COMMENT '店铺UUID',
  `user_id` VARCHAR(36) NOT NULL COMMENT '管理员用户UUID',
  `role_type` VARCHAR(30) NOT NULL DEFAULT 'ADMIN' COMMENT '店铺内角色: OWNER-店长/主管理员, ADMIN-管理员/店员',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_shop_user` (`shop_id`, `user_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_shop_id` (`shop_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='店铺-管理员关联表';

-- 3. product 表增加 shop_id 所属店铺字段与索引
SET @exist_col = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'product' AND COLUMN_NAME = 'shop_id');
SET @sql_stmt = IF(@exist_col = 0, 'ALTER TABLE `product` ADD COLUMN `shop_id` VARCHAR(36) NOT NULL DEFAULT \'s_shop_000000000000000000000000001\' COMMENT \'所属店铺UUID\' AFTER `id`', 'SELECT 1');
PREPARE stmt FROM @sql_stmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist_idx = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'product' AND INDEX_NAME = 'idx_shop_id');
SET @sql_stmt = IF(@exist_idx = 0, 'ALTER TABLE `product` ADD KEY `idx_shop_id` (`shop_id`)', 'SELECT 1');
PREPARE stmt FROM @sql_stmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 4. order_info 表增加 shop_id 店铺字段与索引
SET @exist_col = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order_info' AND COLUMN_NAME = 'shop_id');
SET @sql_stmt = IF(@exist_col = 0, 'ALTER TABLE `order_info` ADD COLUMN `shop_id` VARCHAR(36) NOT NULL DEFAULT \'s_shop_000000000000000000000000001\' COMMENT \'商铺UUID\' AFTER `order_no`', 'SELECT 1');
PREPARE stmt FROM @sql_stmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist_idx = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order_info' AND INDEX_NAME = 'idx_shop_id');
SET @sql_stmt = IF(@exist_idx = 0, 'ALTER TABLE `order_info` ADD KEY `idx_shop_id` (`shop_id`)', 'SELECT 1');
PREPARE stmt FROM @sql_stmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 5. 确保历史旧数据关联到默认首个旗舰店
UPDATE `product` SET `shop_id` = 's_shop_000000000000000000000000001' WHERE `shop_id` IS NULL OR `shop_id` = '';
UPDATE `order_info` SET `shop_id` = 's_shop_000000000000000000000000001' WHERE `shop_id` IS NULL OR `shop_id` = '';

-- 6. 预置更多门店 (西丽万科分店、前海中心店)
INSERT INTO `shop_info` (`id`, `name`, `notice`, `phone`, `business_hours`, `address`, `longitude`, `latitude`, `max_delivery_distance`, `min_order_amount`, `delivery_fee`, `status`, `sort_order`)
VALUES 
('s_shop_000000000000000000000000002', '维琼鲜肉铺(西丽万科分店)', '西丽分店每日早市新鲜排酸热气肉，西丽大学城及周边专人极速送达！', '0755-86667777', '06:00 - 20:30', '深圳市南山区西丽留仙洞万科云城B区05号商铺', 113.9612000, 22.5801000, 6.00, 20.00, 4.00, 1, 90),
('s_shop_000000000000000000000000003', '维琼鲜肉铺(前海中心店)', '前海商圈高品质有机土猪牛肉，支持精品真空分装保鲜！', '0755-82223333', '07:00 - 21:00', '深圳市南山区前海卓越时代广场108', 113.9051000, 22.5298000, 5.00, 25.00, 5.00, 1, 80)
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `address` = VALUES(`address`), `status` = VALUES(`status`);

-- 7. 预置多个管理员账号 (展示一个店铺多个管理员，不同分店不同管理员)
-- 初始密码均为: admin123 ($2a$10$zyQBYYn1TXYtm8CGwQyHsux9OXWVYTczvXPYFpacp4cCV5CFJfPBS)
INSERT INTO `sys_user` (`id`, `username`, `password`, `nickname`, `avatar_url`, `phone`, `role`, `status`)
VALUES 
('u_admin_00000000000000000000000002', 'manager_tech', '$2a$10$zyQBYYn1TXYtm8CGwQyHsux9OXWVYTczvXPYFpacp4cCV5CFJfPBS', '科技园店主管', '', '13800138002', 'ADMIN', 1),
('u_admin_00000000000000000000000003', 'xili_admin', '$2a$10$zyQBYYn1TXYtm8CGwQyHsux9OXWVYTczvXPYFpacp4cCV5CFJfPBS', '西丽店掌柜', '', '13800138003', 'ADMIN', 1)
ON DUPLICATE KEY UPDATE `nickname` = VALUES(`nickname`), `role` = 'ADMIN', `status` = 1;

-- 8. 绑定店铺与管理员关联数据
-- 旗舰店有2个管理员：admin(店长) + manager_tech(主管)
-- 西丽店有2个管理员：admin(超级管理员统管) + xili_admin(西丽店掌柜)
-- 前海店由 admin(超级管理员) 统管
INSERT INTO `shop_admin` (`id`, `shop_id`, `user_id`, `role_type`)
VALUES 
('sa_00000000000000000000000000000001', 's_shop_000000000000000000000000001', 'u_admin_00000000000000000000000001', 'OWNER'),
('sa_00000000000000000000000000000002', 's_shop_000000000000000000000000001', 'u_admin_00000000000000000000000002', 'ADMIN'),
('sa_00000000000000000000000000000003', 's_shop_000000000000000000000000002', 'u_admin_00000000000000000000000001', 'OWNER'),
('sa_00000000000000000000000000000004', 's_shop_000000000000000000000000002', 'u_admin_00000000000000000000000003', 'ADMIN'),
('sa_00000000000000000000000000000005', 's_shop_000000000000000000000000003', 'u_admin_00000000000000000000000001', 'OWNER')
ON DUPLICATE KEY UPDATE `role_type` = VALUES(`role_type`);

-- 9. 为分店补充示范商品
INSERT INTO `product` (`id`, `shop_id`, `category_id`, `name`, `summary`, `description`, `cover_image`, `price`, `original_price`, `unit`, `stock`, `sales`, `specs_json`, `is_on_sale`, `sort_order`)
VALUES
('p_meat_xili_00000000000000000000001', 's_shop_000000000000000000000000002', 'c_pork_000000000000000000000000001', '【西丽店】散养黑土猪前腿肉', '肥瘦相宜 胶原蛋白丰富 包饺子炒肉首选', '西丽分店专供散养土猪前腿肉，肉质细嫩弹牙。', 'https://images.unsplash.com/photo-1607623814075-e51df1bdc82f?w=600', 26.50, 31.00, '斤', 120, 85, '[{"name":"加工方式","options":["整块带走","切薄片","绞肉馅"]}]', 1, 10),
('p_meat_xili_00000000000000000000002', 's_shop_000000000000000000000000002', 'c_beef_000000000000000000000000002', '【西丽店】精选新鲜牛里脊肉', '纯瘦无筋 极嫩爽口 健身儿童营养餐', '当日清晨现宰黄牛里脊，软嫩无筋，低脂高蛋白。', 'https://images.unsplash.com/photo-1544025162-d76694265947?w=600', 52.00, 60.00, '斤', 60, 48, '[{"name":"加工方式","options":["切柳条","切薄片","整块"]}]', 1, 9)
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`);
