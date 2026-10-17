-- ==============================================================
-- 维琼鲜肉铺 - 商品与分类双向强绑定升级脚本
-- 执行库: fresh_meat_shop
-- 说明:
-- 1. 业务定义认定：商品必须与分类强绑定(不可孤立存在)，分类与商品双向绑定(有商品的分类禁止删除)
-- 2. 自动检查并修复可能存在的孤儿商品数据(绑定至系统首个有效分类)
-- 3. 强制 `product.category_id` 为 NOT NULL，并确保普通索引与外键约束
-- 4. 幂等设计，支持在生产或测试库重复安全执行
-- ==============================================================

USE `fresh_meat_shop`;

-- 步骤 1: 确保至少存在一个默认有效分类供孤立商品兜底绑定
INSERT INTO `category` (`id`, `name`, `icon`, `sort_order`, `status`)
SELECT 'c_pork_000000000000000000000000001', '精选土猪肉', '', 100, 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `category` WHERE `status` = 1 LIMIT 1);

-- 步骤 2: 数据清理与修复 —— 将历史存在 NULL、空串或指向不存在分类的商品，安全对齐绑定到首个有效分类
SET @fallback_cat_id = (SELECT `id` FROM `category` WHERE `status` = 1 ORDER BY `sort_order` DESC, `create_time` ASC LIMIT 1);

UPDATE `product` 
SET `category_id` = @fallback_cat_id
WHERE `category_id` IS NULL 
   OR TRIM(`category_id`) = '' 
   OR `category_id` NOT IN (SELECT `id` FROM `category`);

-- 步骤 3: 严格约束 product 表的 category_id 字段为 NOT NULL
ALTER TABLE `product` 
MODIFY COLUMN `category_id` VARCHAR(36) NOT NULL COMMENT '所属分类UUID(外键关联category.id)';

-- 步骤 4: 确保 category_id 索引存在
SET @exist_idx = (
    SELECT COUNT(*) 
    FROM information_schema.STATISTICS 
    WHERE TABLE_SCHEMA = DATABASE() 
      AND TABLE_NAME = 'product' 
      AND INDEX_NAME = 'idx_category'
);
SET @sql_stmt = IF(@exist_idx = 0, 'ALTER TABLE `product` ADD KEY `idx_category` (`category_id`)', 'SELECT 1');
PREPARE stmt FROM @sql_stmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 步骤 5: 安全添加外键约束 fk_product_category (级联更新，限制删除)
SET @exist_fk = (
    SELECT COUNT(*) 
    FROM information_schema.TABLE_CONSTRAINTS 
    WHERE CONSTRAINT_SCHEMA = DATABASE() 
      AND TABLE_NAME = 'product' 
      AND CONSTRAINT_NAME = 'fk_product_category' 
      AND CONSTRAINT_TYPE = 'FOREIGN KEY'
);

SET @sql_fk = IF(@exist_fk = 0, 
    'ALTER TABLE `product` ADD CONSTRAINT `fk_product_category` FOREIGN KEY (`category_id`) REFERENCES `category` (`id`) ON UPDATE CASCADE ON DELETE RESTRICT', 
    'SELECT 1'
);
PREPARE stmt_fk FROM @sql_fk;
EXECUTE stmt_fk;
DEALLOCATE PREPARE stmt_fk;

-- 步骤 6: 升级后数据对齐核验 (查看各分类绑定的商品数量)
SELECT 
    c.id AS category_id,
    c.name AS category_name,
    c.icon AS category_icon,
    c.status AS category_status,
    COUNT(p.id) AS bound_product_count
FROM `category` c
LEFT JOIN `product` p ON c.id = p.category_id
GROUP BY c.id, c.name, c.icon, c.status
ORDER BY c.sort_order DESC;
