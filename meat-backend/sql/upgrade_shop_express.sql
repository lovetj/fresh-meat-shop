-- ============================================================
-- 升级脚本：店铺快递配送配置 + 同城配送范围语义调整
-- 说明：
--   1. express_enabled  是否开通快递配送（1-开通，0-未开通）
--   2. express_fee      快递基础运费（元）
--   3. express_regions  特殊地区 JSON：可实现部分地区加价或直接停发
--                       格式：[{"region":"新疆","fee":15.00,"deliverable":1},
--                              {"region":"西藏","fee":null,"deliverable":0}]
--   4. 「同城配送」保持由 shop_delivery_rule 管理：每一档均需填写截止距离，
--      超出最后一档即不提供同城配送，顾客可选择到店自取或快递配送
-- 执行方式：在已有库上执行一次，全新安装直接用 schema.sql 即可
-- ============================================================

ALTER TABLE `shop_info`
  ADD COLUMN `express_enabled` TINYINT NOT NULL DEFAULT 0 COMMENT '是否开通快递配送: 1-开通, 0-未开通' AFTER `sort_order`,
  ADD COLUMN `express_fee` DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '快递基础运费(元)' AFTER `express_enabled`,
  ADD COLUMN `express_regions` TEXT DEFAULT NULL COMMENT '快递特殊地区规则JSON' AFTER `express_fee`;
