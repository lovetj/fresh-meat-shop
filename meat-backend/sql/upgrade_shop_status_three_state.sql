-- ============================================================================
-- 店铺营业状态重构：两态 -> 三态
--   1-营业中：按「每日营业时间(business_hours)」自动判定，时段内展示营业中，时段外自动展示休息中
--   2-休息中：掌柜主动休息，不受营业时间限制，始终展示休息中（仍可下单）
--   3-歇业中：相当于关门，禁止下单
--
-- 历史值兼容：原 0（休息中）统一迁移为 2（休息中）
-- 说明：status 仅保存掌柜的「手动状态」，实际展示状态由后端结合当前时间实时计算，
--       接口额外返回 displayStatus / statusText / allowOrder / inBusinessHours 字段。
-- ============================================================================

-- 1. 修改字段注释与默认值（TINYINT 足以表达三态，无需改类型）
ALTER TABLE `shop_info`
  MODIFY COLUMN `status` TINYINT NOT NULL DEFAULT 1
  COMMENT '营业状态: 1-营业中(按每日营业时间自动判定), 2-休息中(手动,不受时间限制), 3-歇业中(停止接单)';

-- 2. 历史数据迁移：0（休息中）-> 2（休息中）
UPDATE `shop_info` SET `status` = 2 WHERE `status` = 0;

-- 3. 防御性兜底：空值或非法值回落为营业中（正常不会出现，仅防止手工改库残留）
UPDATE `shop_info` SET `status` = 1 WHERE `status` IS NULL OR `status` NOT IN (1, 2, 3);

-- 4.（可选）状态常被用于门店列表排序，数据量增长后可开启索引
-- ALTER TABLE `shop_info` ADD INDEX `idx_shop_status` (`status`);

-- 5. 校验
-- SELECT `status`, COUNT(*) AS cnt FROM `shop_info` GROUP BY `status`;
