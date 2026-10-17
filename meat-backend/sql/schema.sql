-- ==============================================================
-- 维琼鲜肉铺同城下单配送系统 - 数据库脚本 (UUID主键版本 - 多店铺与多管理员架构)
-- 数据库: fresh_meat_shop
-- 说明: 全表主键采用 UUID (VARCHAR(36))，无自增ID
--       支持多店铺管理、店铺多管理员绑定、商品分店绑定、订单分店核算与配送
-- ==============================================================

CREATE DATABASE IF NOT EXISTS `fresh_meat_shop` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `fresh_meat_shop`;

-- 1. 用户表 (支持微信普通顾客与独立账号密码管理员)
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user` (
  `id` VARCHAR(36) NOT NULL COMMENT '主键UUID',
  `openid` VARCHAR(64) DEFAULT NULL COMMENT '微信openid',
  `unionid` VARCHAR(64) DEFAULT NULL COMMENT '微信开放平台unionid',
  `username` VARCHAR(64) DEFAULT NULL COMMENT '管理员登录账号',
  `password` VARCHAR(128) DEFAULT NULL COMMENT '管理员登录密码(哈希加密)',
  `nickname` VARCHAR(64) DEFAULT '微信顾客' COMMENT '用户昵称',
  `avatar_url` VARCHAR(512) DEFAULT '' COMMENT '用户头像URL',
  `phone` VARCHAR(20) DEFAULT '' COMMENT '联系电话',
  `role` VARCHAR(20) NOT NULL DEFAULT 'CUSTOMER' COMMENT '角色: CUSTOMER-顾客, ADMIN-商家管理员',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1-启用, 0-禁用',
  `last_login_time` DATETIME DEFAULT NULL COMMENT '最近登录时间',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_openid` (`openid`),
  UNIQUE KEY `uk_username` (`username`),
  KEY `idx_role` (`role`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 预置管理员与示范顾客账号 (密码均为 admin123, BCrypt 密文)
INSERT INTO `sys_user` (`id`, `username`, `password`, `nickname`, `avatar_url`, `phone`, `role`, `status`)
VALUES 
('u_admin_00000000000000000000000001', 'admin', '$2a$10$zyQBYYn1TXYtm8CGwQyHsux9OXWVYTczvXPYFpacp4cCV5CFJfPBS', '超级管理员', '', '13800138000', 'ADMIN', 1),
('u_admin_00000000000000000000000002', 'manager_tech', '$2a$10$zyQBYYn1TXYtm8CGwQyHsux9OXWVYTczvXPYFpacp4cCV5CFJfPBS', '科技园店主管', '', '13800138002', 'ADMIN', 1),
('u_admin_00000000000000000000000003', 'xili_admin', '$2a$10$zyQBYYn1TXYtm8CGwQyHsux9OXWVYTczvXPYFpacp4cCV5CFJfPBS', '西丽店掌柜', '', '13800138003', 'ADMIN', 1);

INSERT INTO `sys_user` (`id`, `openid`, `nickname`, `avatar_url`, `phone`, `role`, `status`)
VALUES 
('u_customer_00000000000000000000001', 'customer_openid_demo', '尝鲜顾客小李', '', '13912345678', 'CUSTOMER', 1);

-- 2. 商铺信息配置表 (多店铺，包含店铺地址、经纬度与最大配送范围)
DROP TABLE IF EXISTS `shop_info`;
CREATE TABLE `shop_info` (
  `id` VARCHAR(36) NOT NULL COMMENT '主键UUID',
  `name` VARCHAR(100) NOT NULL DEFAULT '维琼鲜肉铺' COMMENT '店铺名称',
  `notice` VARCHAR(500) DEFAULT '每日清晨现宰直达，新鲜热鲜肉、土猪土牛肉，同城专人极速送达！' COMMENT '店铺公告',
  `phone` VARCHAR(30) NOT NULL DEFAULT '0755-88889999' COMMENT '联系电话',
  `business_hours` VARCHAR(100) DEFAULT '06:00 - 19:30' COMMENT '营业时间',
  `address` VARCHAR(200) NOT NULL DEFAULT '深圳市南山区科技园肉菜综合批发市场A区102' COMMENT '商铺详细地址',
  `longitude` DECIMAL(10, 7) NOT NULL DEFAULT 113.9463160 COMMENT '店铺经度',
  `latitude` DECIMAL(10, 7) NOT NULL DEFAULT 22.5401390 COMMENT '店铺纬度',
  `max_delivery_distance` DECIMAL(6, 2) NOT NULL DEFAULT 6.00 COMMENT '最大配送距离(公里km)',
  `min_order_amount` DECIMAL(10, 2) NOT NULL DEFAULT 20.00 COMMENT '起送金额(元)',
  `delivery_fee` DECIMAL(10, 2) NOT NULL DEFAULT 3.00 COMMENT '默认配送费(元)',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '营业状态: 1-正常营业, 0-休息中',
  `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序权重(越大越前)',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商铺基本信息与配送配置表';

INSERT INTO `shop_info` (`id`, `name`, `notice`, `phone`, `business_hours`, `address`, `longitude`, `latitude`, `max_delivery_distance`, `min_order_amount`, `delivery_fee`, `status`, `sort_order`)
VALUES 
('s_shop_000000000000000000000000001', '维琼鲜肉铺(科技园旗舰店)', '每日清晨新鲜直达，现切现配，排酸冷鲜肉，足斤足两保障！', '13800138000', '06:30 - 20:00', '深圳市南山区科兴科学园肉菜生活馆A01', 113.9435000, 22.5492000, 5.00, 15.00, 3.00, 1, 100),
('s_shop_000000000000000000000000002', '维琼鲜肉铺(西丽万科分店)', '西丽分店每日早市新鲜排酸热气肉，西丽大学城及周边专人极速送达！', '0755-86667777', '06:00 - 20:30', '深圳市南山区西丽留仙洞万科云城B区05号商铺', 113.9612000, 22.5801000, 6.00, 20.00, 4.00, 1, 90),
('s_shop_000000000000000000000000003', '维琼鲜肉铺(前海中心店)', '前海商圈高品质有机土猪牛肉，支持精品真空分装保鲜！', '0755-82223333', '07:00 - 21:00', '深圳市南山区前海卓越时代广场108', 113.9051000, 22.5298000, 5.00, 25.00, 5.00, 1, 80);

-- 3. 店铺-管理员关联表 (每个店铺多个管理员，管理员绑定店铺)
DROP TABLE IF EXISTS `shop_admin`;
CREATE TABLE `shop_admin` (
  `id` VARCHAR(36) NOT NULL COMMENT '关联主键UUID',
  `shop_id` VARCHAR(36) NOT NULL COMMENT '店铺UUID',
  `user_id` VARCHAR(36) NOT NULL COMMENT '管理员用户UUID',
  `role_type` VARCHAR(30) NOT NULL DEFAULT 'ADMIN' COMMENT '店铺内角色: OWNER-店长/主管理员, ADMIN-店员/协同管理员',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_shop_user` (`shop_id`, `user_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_shop_id` (`shop_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='店铺-管理员关联表';

INSERT INTO `shop_admin` (`id`, `shop_id`, `user_id`, `role_type`)
VALUES 
('sa_00000000000000000000000000000001', 's_shop_000000000000000000000000001', 'u_admin_00000000000000000000000001', 'OWNER'),
('sa_00000000000000000000000000000002', 's_shop_000000000000000000000000001', 'u_admin_00000000000000000000000002', 'ADMIN'),
('sa_00000000000000000000000000000003', 's_shop_000000000000000000000000002', 'u_admin_00000000000000000000000001', 'OWNER'),
('sa_00000000000000000000000000000004', 's_shop_000000000000000000000000002', 'u_admin_00000000000000000000000003', 'ADMIN'),
('sa_00000000000000000000000000000005', 's_shop_000000000000000000000000003', 'u_admin_00000000000000000000000001', 'OWNER');

-- 4. 商品分类表 (绑定具体商铺，分店独立维护分类体系)
DROP TABLE IF EXISTS `category`;
CREATE TABLE `category` (
  `id` VARCHAR(36) NOT NULL COMMENT '分类UUID',
  `shop_id` VARCHAR(36) NOT NULL DEFAULT 's_shop_000000000000000000000000001' COMMENT '所属店铺UUID',
  `name` VARCHAR(50) NOT NULL COMMENT '分类名称',
  `icon` VARCHAR(255) DEFAULT '' COMMENT '图标或示意图(允许为空)',
  `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序值(越大越靠前)',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1-启用, 0-停用',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_cat_shop` (`shop_id`),
  CONSTRAINT `fk_category_shop` FOREIGN KEY (`shop_id`) REFERENCES `shop_info` (`id`) ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品分类表';

INSERT INTO `category` (`id`, `shop_id`, `name`, `icon`, `sort_order`, `status`)
VALUES 
-- 总店分类
('c_pork_000000000000000000000000001', 's_shop_000000000000000000000000001', '精选土猪肉', '', 100, 1),
('c_beef_000000000000000000000000002', 's_shop_000000000000000000000000001', '优质鲜牛肉', '', 90, 1),
('c_mutton_00000000000000000000000003', 's_shop_000000000000000000000000001', '鲜嫩羊肉排', '', 80, 1),
('c_poultry_0000000000000000000000004', 's_shop_000000000000000000000000001', '新鲜家禽鸡鸭', '', 70, 1),
('c_mince_000000000000000000000000005', 's_shop_000000000000000000000000001', '肉馅/定制切片', '', 60, 1),
-- 西丽分店独立专属分类
('c_xili_pork_0000000000000000000001', 's_shop_000000000000000000000000002', '西丽·土猪专区', '', 100, 1),
('c_xili_beef_0000000000000000000002', 's_shop_000000000000000000000000002', '西丽·黄牛肉专区', '', 90, 1);

-- 5. 商品表 (包含 shop_id 绑定具体商铺，强绑定 category_id)
DROP TABLE IF EXISTS `product`;
CREATE TABLE `product` (
  `id` VARCHAR(36) NOT NULL COMMENT '商品UUID',
  `shop_id` VARCHAR(36) NOT NULL DEFAULT 's_shop_000000000000000000000000001' COMMENT '所属店铺UUID',
  `category_id` VARCHAR(36) NOT NULL COMMENT '所属分类UUID(外键关联category.id)',
  `name` VARCHAR(120) NOT NULL COMMENT '商品名称',
  `summary` VARCHAR(255) DEFAULT '' COMMENT '商品简述/卖点',
  `description` TEXT COMMENT '商品详细图文介绍',
  `cover_image` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '商品主图',
  `price` DECIMAL(10, 2) NOT NULL COMMENT '当前售价(元)',
  `original_price` DECIMAL(10, 2) DEFAULT NULL COMMENT '原价/划线价(元)',
  `unit` VARCHAR(20) NOT NULL DEFAULT '斤' COMMENT '计价单位(如: 斤/份/盒/块)',
  `stock` INT NOT NULL DEFAULT 999 COMMENT '库存数量',
  `sales` INT NOT NULL DEFAULT 0 COMMENT '已售数量',
  `specs_json` VARCHAR(1000) DEFAULT '[]' COMMENT '可选规格配置(JSON数组，如不同份量或切法)',
  `is_on_sale` TINYINT NOT NULL DEFAULT 1 COMMENT '是否上架: 1-上架, 0-下架',
  `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序(越大越前)',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_shop_id` (`shop_id`),
  KEY `idx_category` (`category_id`),
  KEY `idx_is_on_sale` (`is_on_sale`),
  CONSTRAINT `fk_product_category` FOREIGN KEY (`category_id`) REFERENCES `category` (`id`) ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品信息表';

INSERT INTO `product` (`id`, `shop_id`, `category_id`, `name`, `summary`, `description`, `cover_image`, `price`, `original_price`, `unit`, `stock`, `sales`, `specs_json`, `is_on_sale`, `sort_order`)
VALUES 
('p_meat_000000000000000000000000001', 's_shop_000000000000000000000000001', 'c_pork_000000000000000000000000001', '农家土猪五花肉 (精五花)', '肥瘦相间 五层分明 现切鲜肉 红烧煲汤必备', '正宗农家散养土猪，五花三层，肥而不腻，瘦肉不柴，每天早晨现宰进店，手工现切。', 'https://images.unsplash.com/photo-1607623814075-e51df1bdc82f?w=600', 25.80, 29.80, '斤', 200, 368, '[{"name":"份量","options":["500g(约1斤)","1000g(约2斤)","1500g(约3斤)"]},{"name":"加工方式","options":["整块不切","切小方块(红烧)","切薄片(小炒)"]}]', 1, 10),
('p_meat_000000000000000000000000002', 's_shop_000000000000000000000000001', 'c_pork_000000000000000000000000001', '鲜嫩里脊肉 (纯瘦无油)', '滑嫩可口 辅食炒肉丝首选', '精选猪脊骨内侧整条纯瘦肉，纤维细腻，爆炒、糖醋里脊非常鲜嫩。', 'https://images.unsplash.com/photo-1544025162-d76694265947?w=600', 28.00, 32.00, '斤', 150, 192, '[{"name":"份量","options":["500g(约1斤)","1000g(约2斤)"]},{"name":"加工方式","options":["整条带走","切肉丝","切肉片","绞肉馅"]}]', 1, 9),
('p_meat_000000000000000000000000003', 's_shop_000000000000000000000000001', 'c_pork_000000000000000000000000001', '土猪精排骨 (中段小排)', '肉质紧实 骨香浓郁 清蒸红烧佳选', '优质肋排中段，骨细肉厚，清晨新鲜分剔，绝无冻排。', 'https://images.unsplash.com/photo-1544025162-d76694265947?w=600', 36.50, 42.00, '斤', 100, 520, '[{"name":"份量","options":["500g(约1斤)","1000g(约2斤)"]},{"name":"加工方式","options":["剁小块(约3cm)","整根排骨"]}]', 1, 8),
('p_meat_000000000000000000000000004', 's_shop_000000000000000000000000001', 'c_beef_000000000000000000000000002', '鲜黄牛肉牛腩 (肥瘦适中)', '带筋带腩 炖番茄牛腩/咖喱绝配', '本地新鲜黄牛坑腩，肉香浓郁，炖煮酥烂多汁。', 'https://images.unsplash.com/photo-1588168333986-5078d3ae3976?w=600', 48.00, 55.00, '斤', 80, 140, '[{"name":"份量","options":["500g(约1斤)","1000g(约2斤)"]},{"name":"加工方式","options":["切大块","整块"]}]', 1, 7),
('p_meat_000000000000000000000000005', 's_shop_000000000000000000000000001', 'c_beef_000000000000000000000000002', '现切吊龙牛肉片', '火锅小炒 极品嫩滑 烫涮8秒', '黄牛脊背上一长条肉，肉质极其鲜美，微带细细雪花脂肪。', 'https://images.unsplash.com/photo-1558030006-450675393462?w=600', 58.00, 68.00, '份', 90, 310, '[{"name":"份量","options":["250g(半斤装)","500g(一斤装)"]},{"name":"加工方式","options":["薄切(火锅涮)","稍厚切(爆炒)"]}]', 1, 6),
('p_meat_000000000000000000000000006', 's_shop_000000000000000000000000001', 'c_poultry_0000000000000000000000004', '散养走地土公鸡 (整只现宰)', '山林放养 鸡味纯正 肉质筋道', '现挑现宰，净膛去内脏，煲汤金黄浓郁，也可代剁块。', 'https://images.unsplash.com/photo-1587593810167-a84920ea0781?w=600', 78.00, 88.00, '只', 50, 89, '[{"name":"加工方式","options":["整只带走","剁块煲汤","去鸡头鸡爪"]}]', 1, 5),
-- 西丽分店商品 (绑定西丽店及西丽店专属分类)
('p_meat_xili_00000000000000000000001', 's_shop_000000000000000000000000002', 'c_xili_pork_0000000000000000000001', '【西丽店】散养黑土猪前腿肉', '肥瘦相宜 胶原蛋白丰富 包饺子炒肉首选', '西丽分店专供散养土猪前腿肉，肉质细嫩弹牙。', 'https://images.unsplash.com/photo-1607623814075-e51df1bdc82f?w=600', 26.50, 31.00, '斤', 120, 85, '[{"name":"加工方式","options":["整块带走","切薄片","绞肉馅"]}]', 1, 10),
('p_meat_xili_00000000000000000000002', 's_shop_000000000000000000000000002', 'c_xili_beef_0000000000000000000002', '【西丽店】精选新鲜牛里脊肉', '纯瘦无筋 极嫩爽口 健身儿童营养餐', '当日清晨现宰黄牛里脊，软嫩无筋，低脂高蛋白。', 'https://images.unsplash.com/photo-1544025162-d76694265947?w=600', 52.00, 60.00, '斤', 60, 48, '[{"name":"加工方式","options":["切柳条","切薄片","整块"]}]', 1, 9);

-- 6. 顾客收货地址表 (含地理经纬度，用于同城距离比对)
DROP TABLE IF EXISTS `user_address`;
CREATE TABLE `user_address` (
  `id` VARCHAR(36) NOT NULL COMMENT '地址UUID',
  `user_id` VARCHAR(36) NOT NULL COMMENT '用户UUID',
  `contact_name` VARCHAR(50) NOT NULL COMMENT '联系人姓名',
  `contact_phone` VARCHAR(20) NOT NULL COMMENT '联系电话',
  `province` VARCHAR(50) DEFAULT '' COMMENT '省份',
  `city` VARCHAR(50) DEFAULT '' COMMENT '城市',
  `district` VARCHAR(50) DEFAULT '' COMMENT '区县',
  `address` VARCHAR(200) NOT NULL COMMENT '详细门牌/小区地址',
  `full_address` VARCHAR(300) NOT NULL COMMENT '完整拼接地址',
  `longitude` DECIMAL(10, 7) NOT NULL COMMENT '地址经度',
  `latitude` DECIMAL(10, 7) NOT NULL COMMENT '地址纬度',
  `is_default` TINYINT NOT NULL DEFAULT 0 COMMENT '是否默认: 1-是, 0-否',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='顾客收货地址表';

INSERT INTO `user_address` (`id`, `user_id`, `contact_name`, `contact_phone`, `province`, `city`, `district`, `address`, `full_address`, `longitude`, `latitude`, `is_default`)
VALUES 
('d_addr_000000000000000000000000001', 'u_customer_00000000000000000000001', '李先生', '13912345678', '广东省', '深圳市', '南山区', '科技园科兴科学园G栋1201', '广东省深圳市南山区科技园科兴科学园G栋1201', 113.9458000, 22.5532000, 1);

-- 7. 订单主表 (包含 shop_id 绑定对应店铺)
DROP TABLE IF EXISTS `order_info`;
CREATE TABLE `order_info` (
  `id` VARCHAR(36) NOT NULL COMMENT '订单主键UUID',
  `order_no` VARCHAR(40) NOT NULL COMMENT '唯一订单编号',
  `shop_id` VARCHAR(36) NOT NULL DEFAULT 's_shop_000000000000000000000000001' COMMENT '商铺UUID',
  `user_id` VARCHAR(36) NOT NULL COMMENT '下单顾客UUID',
  `delivery_type` TINYINT NOT NULL COMMENT '配送方式: 1-自取(到店), 2-商家配送, 3-外卖(保留入口)',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '订单状态: 0-待支付, 1-待接单, 2-待配送/待自取, 3-配送中, 4-已完成, 5-已取消, 6-已拒单',
  `pay_type` TINYINT NOT NULL DEFAULT 1 COMMENT '支付方式: 1-微信线上支付, 2-货到付款(现金/到付)',
  `pay_status` TINYINT NOT NULL DEFAULT 0 COMMENT '支付状态: 0-未支付, 1-已支付, 2-已退款',
  
  -- 收货/自取信息
  `receiver_name` VARCHAR(50) DEFAULT '' COMMENT '收货人/自取人姓名',
  `receiver_phone` VARCHAR(20) DEFAULT '' COMMENT '收货人/自取人电话',
  `receiver_address` VARCHAR(300) DEFAULT '' COMMENT '配送详细地址(自取时为空或店铺地址)',
  `receiver_lng` DECIMAL(10, 7) DEFAULT NULL COMMENT '收货地址经度',
  `receiver_lat` DECIMAL(10, 7) DEFAULT NULL COMMENT '收货地址纬度',
  `delivery_distance` DECIMAL(6, 2) DEFAULT 0.00 COMMENT '计算的配送距离(km)',
  `delivery_fee` DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '配送费(元)',
  
  -- 金额计算
  `goods_amount` DECIMAL(10, 2) NOT NULL COMMENT '商品总金额',
  `pay_amount` DECIMAL(10, 2) NOT NULL COMMENT '实付金额(商品总额+配送费)',
  
  -- 订单附注与处理原因
  `remark` VARCHAR(255) DEFAULT '' COMMENT '顾客备注(如切片要求、切丁等)',
  `cancel_reason` VARCHAR(255) DEFAULT '' COMMENT '顾客/系统取消原因',
  `reject_reason` VARCHAR(255) DEFAULT '' COMMENT '商家拒单原因',
  
  -- 时间跟踪
  `pay_time` DATETIME DEFAULT NULL COMMENT '支付完成时间',
  `accept_time` DATETIME DEFAULT NULL COMMENT '商家接单时间',
  `deliver_time` DATETIME DEFAULT NULL COMMENT '开始配送/备好通知时间',
  `finish_time` DATETIME DEFAULT NULL COMMENT '订单完成时间',
  `cancel_time` DATETIME DEFAULT NULL COMMENT '取消时间',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_shop_id` (`shop_id`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单主表';

-- 8. 订单明细表
DROP TABLE IF EXISTS `order_item`;
CREATE TABLE `order_item` (
  `id` VARCHAR(36) NOT NULL COMMENT '主键UUID',
  `order_id` VARCHAR(36) NOT NULL COMMENT '所属订单UUID',
  `product_id` VARCHAR(36) NOT NULL COMMENT '商品UUID',
  `product_name` VARCHAR(120) NOT NULL COMMENT '商品名称',
  `product_image` VARCHAR(512) DEFAULT '' COMMENT '商品图片',
  `spec_info` VARCHAR(255) DEFAULT '' COMMENT '所选规格说明(如: 1斤 / 切小块)',
  `price` DECIMAL(10, 2) NOT NULL COMMENT '购买单价',
  `quantity` INT NOT NULL DEFAULT 1 COMMENT '购买数量/份数',
  `subtotal` DECIMAL(10, 2) NOT NULL COMMENT '小计金额',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_order_id` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单明细表';

-- 9. 支付交易流水表
DROP TABLE IF EXISTS `payment_record`;
CREATE TABLE `payment_record` (
  `id` VARCHAR(36) NOT NULL COMMENT '流水UUID',
  `payment_no` VARCHAR(40) NOT NULL COMMENT '系统支付单号',
  `order_id` VARCHAR(36) NOT NULL COMMENT '订单UUID',
  `order_no` VARCHAR(40) NOT NULL COMMENT '订单编号',
  `user_id` VARCHAR(36) NOT NULL COMMENT '支付用户UUID',
  `pay_type` TINYINT NOT NULL DEFAULT 1 COMMENT '支付方式: 1-微信线上支付, 2-货到付款',
  `amount` DECIMAL(10, 2) NOT NULL COMMENT '交易金额',
  `trade_no` VARCHAR(64) DEFAULT '' COMMENT '微信支付流水号(微信端交易单号)',
  `prepay_id` VARCHAR(64) DEFAULT '' COMMENT '微信预支付会话标识',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '支付状态: 0-待支付, 1-支付成功, 2-支付失败, 3-已退款',
  `callback_content` TEXT COMMENT '微信支付回调原始报文',
  `pay_time` DATETIME DEFAULT NULL COMMENT '支付完成时间',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payment_no` (`payment_no`),
  KEY `idx_order_id` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付流水记录表';
