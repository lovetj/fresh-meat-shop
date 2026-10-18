-- ==============================================================
-- 维琼鲜肉铺 - 同城配送多阶梯规则与范围增量升级脚本
-- 执行库: fresh_meat_shop
-- 说明:
-- 1. 新增 shop_delivery_rule (商铺同城配送规则表)，支持按距离配置多个阶梯规则(起送价、配送费、距离区间)
-- 2. 自动从现有 shop_info 导入历史单阶梯配置为默认第一条规则，并为旗舰店和分店初始化多阶梯规则
-- 3. 幂等设计，支持在生产或测试库安全重复执行
-- ==============================================================

USE `fresh_meat_shop`;

-- 步骤 1: 创建 shop_delivery_rule 表（若不存在）
CREATE TABLE IF NOT EXISTS `shop_delivery_rule` (
  `id` VARCHAR(36) NOT NULL COMMENT '规则UUID',
  `shop_id` VARCHAR(36) NOT NULL COMMENT '所属店铺UUID',
  `min_distance` DECIMAL(6, 2) NOT NULL DEFAULT 0.00 COMMENT '起始距离(km, 包含边界)',
  `max_distance` DECIMAL(6, 2) NULL DEFAULT NULL COMMENT '截止距离(km, 包含边界; 为空或0表示无上限/不限距离)',
  `min_order_amount` DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '该距离区间的起送金额(元)',
  `delivery_fee` DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '该距离区间的配送费(元)',
  `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序权重(通常从小到大)',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_rule_shop` (`shop_id`),
  CONSTRAINT `fk_delivery_rule_shop` FOREIGN KEY (`shop_id`) REFERENCES `shop_info` (`id`) ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商铺同城配送多阶梯规则表';

-- 兼容老表字段调整：允许 max_distance 为 NULL (代表无上限/不限距离)
ALTER TABLE `shop_delivery_rule` MODIFY COLUMN `max_distance` DECIMAL(6, 2) NULL DEFAULT NULL COMMENT '截止距离(km, 包含边界; 为空或0表示无上限/不限距离)';

-- 步骤 2: 为现有店铺迁移/初始化多阶梯配送规则 (避免重复插入)

-- 科技园旗舰店多阶梯配置
-- 0-3km: 起送15元, 配送费3元
INSERT INTO `shop_delivery_rule` (`id`, `shop_id`, `min_distance`, `max_distance`, `min_order_amount`, `delivery_fee`, `sort_order`)
SELECT 'dr_tech_00000000000000000000000001', 's_shop_000000000000000000000000001', 0.00, 3.00, 15.00, 3.00, 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `shop_delivery_rule` WHERE `id` = 'dr_tech_00000000000000000000000001');

-- 3-5km: 起送25元, 配送费5元
INSERT INTO `shop_delivery_rule` (`id`, `shop_id`, `min_distance`, `max_distance`, `min_order_amount`, `delivery_fee`, `sort_order`)
SELECT 'dr_tech_00000000000000000000000002', 's_shop_000000000000000000000000001', 3.00, 5.00, 25.00, 5.00, 2
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `shop_delivery_rule` WHERE `id` = 'dr_tech_00000000000000000000000002');

-- 5-8km: 起送40元, 配送费8元
INSERT INTO `shop_delivery_rule` (`id`, `shop_id`, `min_distance`, `max_distance`, `min_order_amount`, `delivery_fee`, `sort_order`)
SELECT 'dr_tech_00000000000000000000000003', 's_shop_000000000000000000000000001', 5.00, 8.00, 40.00, 8.00, 3
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `shop_delivery_rule` WHERE `id` = 'dr_tech_00000000000000000000000003');

-- 西丽分店多阶梯配置
-- 0-3km: 起送20元, 配送费4元
INSERT INTO `shop_delivery_rule` (`id`, `shop_id`, `min_distance`, `max_distance`, `min_order_amount`, `delivery_fee`, `sort_order`)
SELECT 'dr_xili_00000000000000000000000001', 's_shop_000000000000000000000000002', 0.00, 3.00, 20.00, 4.00, 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `shop_delivery_rule` WHERE `id` = 'dr_xili_00000000000000000000000001');

-- 3-6km: 起送35元, 配送费6元
INSERT INTO `shop_delivery_rule` (`id`, `shop_id`, `min_distance`, `max_distance`, `min_order_amount`, `delivery_fee`, `sort_order`)
SELECT 'dr_xili_00000000000000000000000002', 's_shop_000000000000000000000000002', 3.00, 6.00, 35.00, 6.00, 2
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `shop_delivery_rule` WHERE `id` = 'dr_xili_00000000000000000000000002');

-- 前海中心店多阶梯配置
-- 0-3km: 起送25元, 配送费5元
INSERT INTO `shop_delivery_rule` (`id`, `shop_id`, `min_distance`, `max_distance`, `min_order_amount`, `delivery_fee`, `sort_order`)
SELECT 'dr_qianhai_0000000000000000000001', 's_shop_000000000000000000000000003', 0.00, 3.00, 25.00, 5.00, 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `shop_delivery_rule` WHERE `id` = 'dr_qianhai_0000000000000000000001');

-- 3-5km: 起送40元, 配送费8元
INSERT INTO `shop_delivery_rule` (`id`, `shop_id`, `min_distance`, `max_distance`, `min_order_amount`, `delivery_fee`, `sort_order`)
SELECT 'dr_qianhai_0000000000000000000002', 's_shop_000000000000000000000000003', 3.00, 5.00, 40.00, 8.00, 2
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `shop_delivery_rule` WHERE `id` = 'dr_qianhai_0000000000000000000002');

-- 5km以上(不限距离/全城送): 起送60元, 配送费12元
INSERT INTO `shop_delivery_rule` (`id`, `shop_id`, `min_distance`, `max_distance`, `min_order_amount`, `delivery_fee`, `sort_order`)
SELECT 'dr_qianhai_0000000000000000000003', 's_shop_000000000000000000000000003', 5.00, NULL, 60.00, 12.00, 3
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `shop_delivery_rule` WHERE `id` = 'dr_qianhai_0000000000000000000003');

-- 步骤 3: 存量兜底迁移：若有未配置规则的店铺，自动将其 shop_info 表中的历史字段转为第一条规则
INSERT INTO `shop_delivery_rule` (`id`, `shop_id`, `min_distance`, `max_distance`, `min_order_amount`, `delivery_fee`, `sort_order`)
SELECT 
    CONCAT('dr_auto_', SUBSTRING(REPLACE(UUID(), '-', ''), 1, 24)),
    s.id,
    0.00,
    IFNULL(s.max_delivery_distance, 5.00),
    IFNULL(s.min_order_amount, 20.00),
    IFNULL(s.delivery_fee, 3.00),
    1
FROM `shop_info` s
WHERE NOT EXISTS (
    SELECT 1 FROM `shop_delivery_rule` r WHERE r.shop_id = s.id
);

-- 步骤 4: 同步刷新 shop_info 的最大配送距离字段为当前规则中的最大距离(若含无上限规则则设为0表示不限距离)
UPDATE `shop_info` s
JOIN (
    SELECT 
        shop_id, 
        CASE 
            WHEN SUM(CASE WHEN max_distance IS NULL OR max_distance <= 0 THEN 1 ELSE 0 END) > 0 THEN 0.00
            ELSE MAX(max_distance) 
        END AS max_dist
    FROM `shop_delivery_rule`
    GROUP BY shop_id
) r ON s.id = r.shop_id
SET s.max_delivery_distance = r.max_dist;

-- 步骤 5: 核验输出所有店铺的配送阶梯规则列表
SELECT 
    s.id AS shop_id,
    s.name AS shop_name,
    r.id AS rule_id,
    r.min_distance,
    r.max_distance,
    r.min_order_amount,
    r.delivery_fee,
    r.sort_order
FROM `shop_info` s
LEFT JOIN `shop_delivery_rule` r ON s.id = r.shop_id
ORDER BY s.sort_order DESC, r.min_distance ASC;
