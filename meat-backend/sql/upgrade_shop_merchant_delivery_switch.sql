-- ============================================================
-- 升级脚本：店铺「同城商家配送」开关
-- 说明：
--   1. merchant_delivery_enabled  是否开通同城商家配送（1-开通，0-未开通）
--      · 关闭后顾客端不再提供「商家配送」下单方式，距离校验统一返回不可配送
--      · 关闭不同城配送时，shop_delivery_rule 中的阶梯规则仍保留，重新开启后继续生效
--   2. 与 express_enabled（快递配送开关）保持一致的开关语义
-- 执行方式：在已有库上执行一次，全新安装直接用 schema.sql 即可
-- ============================================================

ALTER TABLE `shop_info`
  ADD COLUMN `merchant_delivery_enabled` TINYINT NOT NULL DEFAULT 1
    COMMENT '是否开通同城商家配送: 1-开通, 0-未开通' AFTER `sort_order`;
