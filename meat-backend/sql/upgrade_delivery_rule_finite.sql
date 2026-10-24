-- ============================================================
-- 升级脚本：取消同城配送「不限距离(无上限)」配置
-- 说明：
--   1. 配送规则每一档都必须有明确的截止距离，超出最后一档即不配送同城
--   2. 历史数据中 max_distance 为 NULL 或 <= 0 的「不限距离」阶梯，
--      按「起始距离 + 5km」补齐为有限阶梯，保证数据合法
--   3. 若补齐后与下一档冲突（同一门店存在多档无上限），仅会保留补齐值，
--      建议执行后到「商家信息 → 同城配送规则」核对一遍
-- 执行方式：在已有库上执行一次
-- ============================================================

UPDATE `shop_delivery_rule`
SET `max_distance` = `min_distance` + 5.00
WHERE `max_distance` IS NULL OR `max_distance` <= 0;

ALTER TABLE `shop_delivery_rule`
  MODIFY COLUMN `max_distance` DECIMAL(6, 2) NOT NULL DEFAULT 0.00 COMMENT '截止距离(km)';
