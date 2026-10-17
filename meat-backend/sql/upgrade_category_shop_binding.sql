-- ==============================================================
-- 维琼鲜肉铺 - 分类与店铺绑定（分店独立分类）增量升级脚本
-- 执行库: fresh_meat_shop
-- 说明:
-- 1. category 表新增 shop_id 字段与索引，确立“每个店铺独立维护自己的分类体系”
-- 2. 存量分类安全归属到各自主营业店铺
-- 3. 自动为其他分店（如西丽分店等）初始化专属分类，并迁移对应店铺的商品归属
-- 4. 幂等设计，支持在生产或测试库安全执行
-- ==============================================================

USE `fresh_meat_shop`;

-- 步骤 1: category 表增加 shop_id 字段
SET @exist_col = (
    SELECT COUNT(*) 
    FROM information_schema.COLUMNS 
    WHERE TABLE_SCHEMA = DATABASE() 
      AND TABLE_NAME = 'category' 
      AND COLUMN_NAME = 'shop_id'
);
SET @sql_stmt = IF(@exist_col = 0, 
    'ALTER TABLE `category` ADD COLUMN `shop_id` VARCHAR(36) NOT NULL DEFAULT \'s_shop_000000000000000000000000001\' COMMENT \'所属店铺UUID\' AFTER `id`', 
    'SELECT 1'
);
PREPARE stmt FROM @sql_stmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 步骤 2: 确保 category 表的 shop_id 索引存在
SET @exist_idx = (
    SELECT COUNT(*) 
    FROM information_schema.STATISTICS 
    WHERE TABLE_SCHEMA = DATABASE() 
      AND TABLE_NAME = 'category' 
      AND INDEX_NAME = 'idx_cat_shop'
);
SET @sql_stmt = IF(@exist_idx = 0, 
    'ALTER TABLE `category` ADD KEY `idx_cat_shop` (`shop_id`)', 
    'SELECT 1'
);
PREPARE stmt FROM @sql_stmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 步骤 3: 确保 category 的 shop_id 外键关联 shop_info(id)
SET @exist_fk = (
    SELECT COUNT(*) 
    FROM information_schema.TABLE_CONSTRAINTS 
    WHERE CONSTRAINT_SCHEMA = DATABASE() 
      AND TABLE_NAME = 'category' 
      AND CONSTRAINT_NAME = 'fk_category_shop' 
      AND CONSTRAINT_TYPE = 'FOREIGN KEY'
);
SET @sql_fk = IF(@exist_fk = 0, 
    'ALTER TABLE `category` ADD CONSTRAINT `fk_category_shop` FOREIGN KEY (`shop_id`) REFERENCES `shop_info` (`id`) ON UPDATE CASCADE ON DELETE RESTRICT', 
    'SELECT 1'
);
PREPARE stmt_fk FROM @sql_fk;
EXECUTE stmt_fk;
DEALLOCATE PREPARE stmt_fk;

-- 步骤 4: 为西丽分店预置专属独立分类（若尚未存在）
INSERT INTO `category` (`id`, `shop_id`, `name`, `icon`, `sort_order`, `status`)
SELECT 'c_xili_pork_0000000000000000000001', 's_shop_000000000000000000000000002', '西丽·土猪专区', '', 100, 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `category` WHERE `id` = 'c_xili_pork_0000000000000000000001');

INSERT INTO `category` (`id`, `shop_id`, `name`, `icon`, `sort_order`, `status`)
SELECT 'c_xili_beef_0000000000000000000002', 's_shop_000000000000000000000000002', '西丽·黄牛肉专区', '', 90, 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `category` WHERE `id` = 'c_xili_beef_0000000000000000000002');

-- 步骤 5: 将西丽分店原有商品重定向对齐到西丽分店专属分类
UPDATE `product` 
SET `category_id` = 'c_xili_pork_0000000000000000000001'
WHERE `id` = 'p_meat_xili_00000000000000000000001';

UPDATE `product` 
SET `category_id` = 'c_xili_beef_0000000000000000000002'
WHERE `id` = 'p_meat_xili_00000000000000000000002';

-- 步骤 6: 核验输出：各店铺分类与绑定商品清单
SELECT 
    s.id AS shop_id,
    s.name AS shop_name,
    c.id AS category_id,
    c.name AS category_name,
    c.status AS category_status,
    COUNT(p.id) AS product_count
FROM `shop_info` s
JOIN `category` c ON s.id = c.shop_id
LEFT JOIN `product` p ON c.id = p.category_id
GROUP BY s.id, s.name, c.id, c.name, c.status
ORDER BY s.sort_order DESC, c.sort_order DESC;
