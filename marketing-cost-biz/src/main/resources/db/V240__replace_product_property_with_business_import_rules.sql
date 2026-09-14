-- V240：产品属性改为业务年度清单，并将上浮比例独立成年度规则。
-- 唯一口径：business_unit_type + property_year + product_code。

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `lp_product_property_rule` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `business_unit_type` VARCHAR(32) NOT NULL COMMENT '业务单元：COMMERCIAL/HOUSEHOLD',
  `property_year` INT NOT NULL COMMENT '规则年度',
  `product_attr` VARCHAR(32) NOT NULL COMMENT '产品属性',
  `uplift_rate` DECIMAL(10,6) NOT NULL DEFAULT 0 COMMENT '上浮比例，0.05 表示 5%',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_product_property_rule_year_attr`
    (`business_unit_type`, `property_year`, `product_attr`),
  KEY `idx_product_property_rule_year` (`business_unit_type`, `property_year`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='年度产品属性上浮规则';

INSERT INTO `lp_product_property_rule`
  (`business_unit_type`, `property_year`, `product_attr`, `uplift_rate`)
VALUES
  ('COMMERCIAL', 2026, '非标品', 0.050000),
  ('COMMERCIAL', 2026, '标准品', 0.000000),
  ('COMMERCIAL', 2026, '定制品', 0.050000),
  ('COMMERCIAL', 2026, 'OEM',    0.000000)
ON DUPLICATE KEY UPDATE `uplift_rate` = VALUES(`uplift_rate`);

-- OA 年用量链路生成的占位行不再是产品属性来源，先清除再收口结构。
DELETE FROM `lp_product_property`
 WHERE `annual_usage_source_type` = 'OA_QUOTE_USAGE'
   AND (`product_attr` IS NULL OR `product_attr` = '' OR `product_attr` = '待维护');

ALTER TABLE `lp_product_property`
  CHANGE COLUMN `attr_source_type` `source_type` VARCHAR(32) DEFAULT NULL
    COMMENT '数据来源：BUSINESS_EXCEL',
  CHANGE COLUMN `attr_source_batch_no` `source_batch_no` VARCHAR(128) DEFAULT NULL
    COMMENT '业务导入批次号',
  RENAME INDEX `idx_product_property_attr_source` TO `idx_product_property_source`,
  DROP COLUMN `level1_code`,
  DROP COLUMN `level1_name`,
  DROP COLUMN `parent_code`,
  DROP COLUMN `parent_name`,
  DROP COLUMN `parent_spec`,
  DROP COLUMN `parent_model`,
  DROP COLUMN `period`,
  DROP COLUMN `annual_usage`,
  DROP COLUMN `annual_usage_source_type`,
  DROP COLUMN `annual_usage_source_batch_no`,
  DROP COLUMN `annual_usage_oa_no`,
  DROP COLUMN `annual_usage_oa_line_id`,
  DROP COLUMN `annual_usage_updated_at`,
  DROP COLUMN `effective_from`,
  DROP COLUMN `effective_to`,
  DROP COLUMN `match_risk_flag`,
  DROP COLUMN `match_risk_reason`,
  DROP COLUMN `coefficient`;

ALTER TABLE `lp_product_property`
  MODIFY COLUMN `business_unit_type` VARCHAR(32) NOT NULL,
  MODIFY COLUMN `property_year` INT NOT NULL,
  MODIFY COLUMN `product_code` VARCHAR(80) NOT NULL,
  MODIFY COLUMN `product_attr` VARCHAR(32) NOT NULL,
  MODIFY COLUMN `business_division` VARCHAR(120) NOT NULL,
  ADD KEY `idx_product_property_year_attr`
    (`business_unit_type`, `property_year`, `product_attr`);

-- 产品清单只允许业务 Excel 导入；删除已废弃的单条新增、删除权限点。
DELETE rm
  FROM `sys_role_menu` rm
  JOIN `sys_menu` menu ON menu.menu_id = rm.menu_id
 WHERE menu.perms IN ('base:product-property:add', 'base:product-property:remove');

DELETE FROM `sys_menu`
 WHERE `perms` IN ('base:product-property:add', 'base:product-property:remove');
