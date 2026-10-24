-- ============================================================
-- 升级脚本：店铺「外卖配送」开关
-- 说明：
--   1. takeout_enabled  是否开通外卖配送（1-开通，0-未开通）
--      · 第三方外卖平台对接需求尚未确认，当前仅作为商家端配置项预留
--      · 顾客端「外卖配送」下单方式暂未开放，开启后不影响现有下单流程
--   2. 与 merchant_delivery_enabled（同城商家配送）、express_enabled（快递配送）
--      保持一致的开关语义，三个配送方式独立控制
-- 执行方式：在已有库上执行一次，全新安装直接用 schema.sql 即可
-- ============================================================

ALTER TABLE `shop_info`
  ADD COLUMN `takeout_enabled` TINYINT NOT NULL DEFAULT 0
    COMMENT '是否开通外卖配送: 1-开通, 0-未开通(需求待定, 暂作配置预留)' AFTER `merchant_delivery_enabled`;
