package com.freshmeat.config;

import cn.hutool.crypto.digest.BCrypt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 数据库自检与管理员鉴权平滑初始化组件
 * 
 * 作用：
 * 1. 自动检测 sys_user 是否包含 username, password 列，若缺失自动 ALTER TABLE 补充；
 * 2. 自动调整 openid 允许 NULL；
 * 3. 自动检测并初始化超级管理员账号 (admin / admin123)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminDatabaseInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            log.info("开始执行管理员鉴权数据库结构自检...");

            // 1. 修改 openid 为允许 NULL
            try {
                jdbcTemplate.execute("ALTER TABLE `sys_user` MODIFY COLUMN `openid` VARCHAR(64) DEFAULT NULL COMMENT '微信openid'");
            } catch (Exception e) {
                log.debug("修改 openid 字段属性跳过或已更新: {}", e.getMessage());
            }

            // 2. 检测并添加 username 字段
            Integer hasUsername = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'username'",
                    Integer.class
            );
            if (hasUsername == null || hasUsername == 0) {
                log.info("检测到 sys_user 表缺少 username 列，自动执行添加...");
                jdbcTemplate.execute("ALTER TABLE `sys_user` ADD COLUMN `username` VARCHAR(64) DEFAULT NULL COMMENT '管理员登录账号' AFTER `unionid`");
                try {
                    jdbcTemplate.execute("ALTER TABLE `sys_user` ADD UNIQUE KEY `uk_username` (`username`)");
                } catch (Exception ignored) {}
            }

            // 3. 检测并添加 password 字段
            Integer hasPassword = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'password'",
                    Integer.class
            );
            if (hasPassword == null || hasPassword == 0) {
                log.info("检测到 sys_user 表缺少 password 列，自动执行添加...");
                jdbcTemplate.execute("ALTER TABLE `sys_user` ADD COLUMN `password` VARCHAR(128) DEFAULT NULL COMMENT '管理员登录密码(哈希加密)' AFTER `username`");
            }

            // 4. 检查是否已有 admin 账号，若无则自动插入，若已有但密码为旧伪哈希或非正确哈希则自动修复
            final String correctHash = "$2a$10$zyQBYYn1TXYtm8CGwQyHsux9OXWVYTczvXPYFpacp4cCV5CFJfPBS"; // admin123
            Integer adminCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM `sys_user` WHERE `username` = 'admin'",
                    Integer.class
            );
            if (adminCount == null || adminCount == 0) {
                log.info("初始化预置超级管理员账号: admin (初始密码: admin123)");
                jdbcTemplate.update(
                        "INSERT INTO `sys_user` (`username`, `password`, `nickname`, `avatar_url`, `phone`, `role`, `status`) VALUES (?, ?, ?, '', '13800138000', 'ADMIN', 1)",
                        "admin", correctHash, "超级管理员"
                );
            } else {
                // 校验已有 admin 密码是否能够匹配 admin123，若不能匹配则自动修复更新
                try {
                    String currentPwd = jdbcTemplate.queryForObject(
                            "SELECT `password` FROM `sys_user` WHERE `username` = 'admin' LIMIT 1",
                            String.class
                    );
                    boolean valid = false;
                    if (currentPwd != null && !currentPwd.trim().isEmpty()) {
                        try {
                            valid = BCrypt.checkpw("admin123", currentPwd);
                        } catch (Exception ignored) {}
                    }
                    if (!valid) {
                        log.info("检测到 admin 账号密码哈希异常或为旧伪哈希，正在自动修复为标准 admin123 哈希...");
                        jdbcTemplate.update("UPDATE `sys_user` SET `password` = ?, `role` = 'ADMIN', `status` = 1 WHERE `username` = 'admin'", correctHash);
                    }
                } catch (Exception e) {
                    log.warn("检查 admin 密码失败: {}", e.getMessage());
                }
            }

            // 5. 将旧 demo openid 强制收回管理员权限，避免任何微信 openid 误登管理端
            jdbcTemplate.update("UPDATE `sys_user` SET `role` = 'CUSTOMER' WHERE `openid` = 'admin_openid_demo'");

            // 6. 多商铺与多管理员架构自检
            log.info("开始执行多店铺与商品绑定架构自检...");
            // (1) 创建 shop_admin 表
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS `shop_admin` (" +
                    "  `id` VARCHAR(36) NOT NULL COMMENT '关联主键UUID'," +
                    "  `shop_id` VARCHAR(36) NOT NULL COMMENT '店铺UUID'," +
                    "  `user_id` VARCHAR(36) NOT NULL COMMENT '管理员用户UUID'," +
                    "  `role_type` VARCHAR(30) NOT NULL DEFAULT 'ADMIN' COMMENT '店铺内角色: OWNER-店长, ADMIN-管理员/店员'," +
                    "  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间'," +
                    "  PRIMARY KEY (`id`)," +
                    "  UNIQUE KEY `uk_shop_user` (`shop_id`, `user_id`)," +
                    "  KEY `idx_user_id` (`user_id`)," +
                    "  KEY `idx_shop_id` (`shop_id`)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='店铺-管理员关联表'");

            // (2) shop_info 增加 sort_order 列
            Integer hasSortOrder = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shop_info' AND COLUMN_NAME = 'sort_order'",
                    Integer.class
            );
            if (hasSortOrder == null || hasSortOrder == 0) {
                jdbcTemplate.execute("ALTER TABLE `shop_info` ADD COLUMN `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序权重(越大越前)' AFTER `status`");
            }

            // (3) product 增加 shop_id 列与索引
            Integer hasProductShopId = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'product' AND COLUMN_NAME = 'shop_id'",
                    Integer.class
            );
            if (hasProductShopId == null || hasProductShopId == 0) {
                log.info("检测到 product 表缺少 shop_id 列，自动执行添加与历史数据兼容...");
                jdbcTemplate.execute("ALTER TABLE `product` ADD COLUMN `shop_id` VARCHAR(36) NOT NULL DEFAULT 's_shop_000000000000000000000000001' COMMENT '所属店铺UUID' AFTER `id`");
                try {
                    jdbcTemplate.execute("ALTER TABLE `product` ADD KEY `idx_shop_id` (`shop_id`)");
                } catch (Exception ignored) {}
            }

            // (4) order_info 增加 shop_id 列与索引
            Integer hasOrderShopId = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order_info' AND COLUMN_NAME = 'shop_id'",
                    Integer.class
            );
            if (hasOrderShopId == null || hasOrderShopId == 0) {
                log.info("检测到 order_info 表缺少 shop_id 列，自动执行添加与历史数据兼容...");
                jdbcTemplate.execute("ALTER TABLE `order_info` ADD COLUMN `shop_id` VARCHAR(36) NOT NULL DEFAULT 's_shop_000000000000000000000000001' COMMENT '商铺UUID' AFTER `order_no`");
                try {
                    jdbcTemplate.execute("ALTER TABLE `order_info` ADD KEY `idx_shop_id` (`shop_id`)");
                } catch (Exception ignored) {}
            }

            // (5) 初始化多店铺示例数据
            Integer shopCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM `shop_info`", Integer.class);
            if (shopCount == null || shopCount <= 1) {
                log.info("初始化预置多门店示例 (西丽万科分店、前海中心店)...");
                jdbcTemplate.update("INSERT INTO `shop_info` (`id`, `name`, `notice`, `phone`, `business_hours`, `address`, `longitude`, `latitude`, `max_delivery_distance`, `min_order_amount`, `delivery_fee`, `status`, `sort_order`) " +
                        "VALUES ('s_shop_000000000000000000000000002', '维琼鲜肉铺(西丽万科分店)', '西丽分店每日早市新鲜排酸热气肉，西丽大学城及周边专人极速送达！', '0755-86667777', '06:00 - 20:30', '深圳市南山区西丽留仙洞万科云城B区05号商铺', 113.9612000, 22.5801000, 6.00, 20.00, 4.00, 1, 90) " +
                        "ON DUPLICATE KEY UPDATE `name` = VALUES(`name`)");
                jdbcTemplate.update("INSERT INTO `shop_info` (`id`, `name`, `notice`, `phone`, `business_hours`, `address`, `longitude`, `latitude`, `max_delivery_distance`, `min_order_amount`, `delivery_fee`, `status`, `sort_order`) " +
                        "VALUES ('s_shop_000000000000000000000000003', '维琼鲜肉铺(前海中心店)', '前海商圈高品质有机土猪牛肉，支持精品真空分装保鲜！', '0755-82223333', '07:00 - 21:00', '深圳市南山区前海卓越时代广场108', 113.9051000, 22.5298000, 5.00, 25.00, 5.00, 1, 80) " +
                        "ON DUPLICATE KEY UPDATE `name` = VALUES(`name`)");
            }

            log.info("管理员鉴权与多店铺数据库结构自检完成！超级管理员账号 (admin / admin123) 与分店已就绪。");
        } catch (Exception e) {
            log.warn("管理员鉴权数据库自检轻微异常(可能受限于只读权限或连接延迟): {}", e.getMessage());
        }
    }
}
