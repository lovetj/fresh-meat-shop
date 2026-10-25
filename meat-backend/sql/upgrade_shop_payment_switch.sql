-- ============================================================
-- 升级脚本：店铺「配送方式 × 支付方式」开关
-- 说明：
--   1. 四种配送方式（到店自取 / 同城商家配送 / 外卖配送 / 快递配送）
--      各自独立配置「微信线上支付」「货到付款」开关
--   2. 字段含义：1-开启，0-关闭
--      · 微信线上支付默认关闭（需微信商户号配置完成后再开启）
--      · 货到付款默认开启（保持现有下单体验不变）
--   3. 顾客结算页只展示当前配送方式下已开启的支付方式，
--      后端下单与下单前校验接口会二次校验，防止绕过前端提交
-- 执行方式：在已有库上执行一次，全新安装直接用 schema.sql 即可
-- ============================================================

ALTER TABLE `shop_info`
  ADD COLUMN `pickup_wxpay_enabled` TINYINT NOT NULL DEFAULT 0
    COMMENT '到店自取-微信线上支付: 1-开启, 0-关闭' AFTER `reserve_days`,
  ADD COLUMN `pickup_cod_enabled` TINYINT NOT NULL DEFAULT 1
    COMMENT '到店自取-货到付款: 1-开启, 0-关闭' AFTER `pickup_wxpay_enabled`,
  ADD COLUMN `merchant_wxpay_enabled` TINYINT NOT NULL DEFAULT 0
    COMMENT '同城商家配送-微信线上支付: 1-开启, 0-关闭' AFTER `pickup_cod_enabled`,
  ADD COLUMN `merchant_cod_enabled` TINYINT NOT NULL DEFAULT 1
    COMMENT '同城商家配送-货到付款: 1-开启, 0-关闭' AFTER `merchant_wxpay_enabled`,
  ADD COLUMN `takeout_wxpay_enabled` TINYINT NOT NULL DEFAULT 0
    COMMENT '外卖配送-微信线上支付: 1-开启, 0-关闭' AFTER `merchant_cod_enabled`,
  ADD COLUMN `takeout_cod_enabled` TINYINT NOT NULL DEFAULT 1
    COMMENT '外卖配送-货到付款: 1-开启, 0-关闭' AFTER `takeout_wxpay_enabled`,
  ADD COLUMN `express_wxpay_enabled` TINYINT NOT NULL DEFAULT 0
    COMMENT '快递配送-微信线上支付: 1-开启, 0-关闭' AFTER `takeout_cod_enabled`,
  ADD COLUMN `express_cod_enabled` TINYINT NOT NULL DEFAULT 1
    COMMENT '快递配送-货到付款: 1-开启, 0-关闭' AFTER `express_wxpay_enabled`;

-- 历史数据兜底：货到付款统一开启，微信线上支付统一关闭
UPDATE `shop_info`
SET `pickup_wxpay_enabled` = 0,
    `pickup_cod_enabled` = 1,
    `merchant_wxpay_enabled` = 0,
    `merchant_cod_enabled` = 1,
    `takeout_wxpay_enabled` = 0,
    `takeout_cod_enabled` = 1,
    `express_wxpay_enabled` = 0,
    `express_cod_enabled` = 1
WHERE `pickup_cod_enabled` IS NULL
   OR `merchant_cod_enabled` IS NULL
   OR `takeout_cod_enabled` IS NULL
   OR `express_cod_enabled` IS NULL;
