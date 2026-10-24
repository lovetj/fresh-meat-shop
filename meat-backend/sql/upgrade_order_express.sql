-- ============================================================
-- 升级脚本：新增「快递配送」配送方式（delivery_type = 4）
-- 说明：
--   1. 新增快递公司、快递单号两个字段，由掌柜接单后手动填写发货
--   2. 仅调整枚举注释，不影响历史订单数据
-- 执行方式：在已有库上执行一次，全新安装直接用 schema.sql 即可
-- ============================================================

ALTER TABLE `order_info`
  ADD COLUMN `express_company` VARCHAR(50) DEFAULT NULL COMMENT '快递公司名称(快递配送)' AFTER `pickup_code`,
  ADD COLUMN `express_no` VARCHAR(64) DEFAULT NULL COMMENT '快递单号(快递配送)' AFTER `express_company`;

ALTER TABLE `order_info`
  MODIFY COLUMN `delivery_type` TINYINT NOT NULL COMMENT '配送方式: 1-自取(到店), 2-商家配送, 3-外卖(保留入口), 4-快递配送';
