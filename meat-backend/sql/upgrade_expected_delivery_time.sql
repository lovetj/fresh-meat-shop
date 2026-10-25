-- ============================================================
-- 升级脚本：顾客期望送达/到店自取时间 + 店铺预约时效配置
-- 说明：
--   1. 店铺侧新增 5 个预约时效配置，掌柜可在「店铺设置」中自行调整：
--      · pickup_min_minutes   到店自取最小备货时间(分钟)：顾客可选期望自取时间 >= 当前时间 + 该值
--      · merchant_min_minutes 同城商家配送最小送达时间(分钟)
--      · takeout_min_minutes  外卖配送最小送达时间(分钟)
--      · time_slot_minutes    期望时间可选档位间隔(分钟)：如 30 表示 10:00 / 10:30 / 11:00 ...
--      · reserve_days         可预约天数(含当天)：0-仅当天, 1-当天+次日 ...
--   2. 订单侧新增 expected_time，记录顾客下单时选择的期望送达/自取时间，
--      掌柜端订单列表与订单详情按该时间排单。
--   3. 可选时间范围 = 每日营业时间内、按档位间隔铺开、且不早于「当前时间 + 对应最小分钟数」。
-- 执行方式：在已有库上执行一次，全新安装直接用 schema.sql 即可
-- ============================================================

ALTER TABLE `shop_info`
  ADD COLUMN `pickup_min_minutes` INT NOT NULL DEFAULT 30
    COMMENT '到店自取最小备货时间(分钟)' AFTER `express_regions`,
  ADD COLUMN `merchant_min_minutes` INT NOT NULL DEFAULT 45
    COMMENT '同城商家配送最小送达时间(分钟)' AFTER `pickup_min_minutes`,
  ADD COLUMN `takeout_min_minutes` INT NOT NULL DEFAULT 45
    COMMENT '外卖配送最小送达时间(分钟)' AFTER `merchant_min_minutes`,
  ADD COLUMN `time_slot_minutes` INT NOT NULL DEFAULT 30
    COMMENT '期望时间可选档位间隔(分钟)' AFTER `takeout_min_minutes`,
  ADD COLUMN `reserve_days` INT NOT NULL DEFAULT 1
    COMMENT '可预约天数(含当天): 0-仅当天, 1-当天+次日' AFTER `time_slot_minutes`;

ALTER TABLE `order_info`
  ADD COLUMN `expected_time` DATETIME DEFAULT NULL
    COMMENT '顾客期望送达/到店自取时间' AFTER `pickup_code`;

-- 历史店铺无需额外刷数：新增列均为 NOT NULL + 默认值，存量行会自动取默认时效。
-- 如需按门店差异化调整，可由掌柜在小程序「店铺设置 - 预约时间与送达时效」中修改，
-- 也可在此处显式更新，例如：
-- UPDATE `shop_info` SET `pickup_min_minutes` = 20, `merchant_min_minutes` = 40
-- WHERE `id` = 's_shop_000000000000000000000000001';

-- 说明：pickup_min_minutes / merchant_min_minutes / takeout_min_minutes 允许为 0（表示无需等待）。
-- 订单 expected_time 为历史订单保持 NULL，前端仅在存在值时展示。
