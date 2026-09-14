-- V241：质量损失率改为业务 Excel“报价系统展示”的裸品料号规则。
-- 唯一口径：business_unit_type + rate_year + bare_product_code。

SET NAMES utf8mb4;

DROP TABLE IF EXISTS `lp_quality_loss_rate_v241`;

CREATE TABLE `lp_quality_loss_rate_v241` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `business_unit_type` VARCHAR(32) NOT NULL COMMENT '业务单元租户',
  `rate_year` INT NOT NULL COMMENT '规则年度',
  `bare_product_code` VARCHAR(80) NOT NULL COMMENT '裸品料号，唯一核算匹配键',
  `product_name` VARCHAR(120) DEFAULT NULL COMMENT '品名',
  `material_spec` VARCHAR(255) DEFAULT NULL COMMENT '物料规格（Excel C列）',
  `product_model` VARCHAR(255) DEFAULT NULL COMMENT '型号，仅展示',
  `business_division` VARCHAR(120) DEFAULT NULL COMMENT '事业部',
  `product_category` VARCHAR(120) DEFAULT NULL COMMENT '大类',
  `product_subcategory` VARCHAR(120) DEFAULT NULL COMMENT '小类',
  `category_spec` VARCHAR(255) DEFAULT NULL COMMENT '分类规格（Excel H列）',
  `fourth_level` VARCHAR(120) DEFAULT NULL COMMENT '四级',
  `loss_rate` DECIMAL(18,12) NOT NULL COMMENT '净损失率，0.01 表示 1%',
  `remark` VARCHAR(500) DEFAULT NULL,
  `source_type` VARCHAR(32) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL/EXCEL_IMPORT',
  `source_batch_no` VARCHAR(128) DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_quality_loss_bare_product`
    (`business_unit_type`, `rate_year`, `bare_product_code`),
  KEY `idx_quality_loss_year_division`
    (`business_unit_type`, `rate_year`, `business_division`),
  KEY `idx_quality_loss_model`
    (`business_unit_type`, `rate_year`, `product_model`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='报价质量损失率裸品规则';

-- 仅迁移能够由当前有效 U9 主档明确解析为裸品的旧料号记录。
-- 旧型号级、LEGACY 记录以及无法解析裸品的记录不再保留。
INSERT IGNORE INTO `lp_quality_loss_rate_v241` (
  `id`, `business_unit_type`, `rate_year`, `bare_product_code`,
  `product_name`, `material_spec`, `product_model`, `business_division`,
  `product_category`, `product_subcategory`, `loss_rate`, `remark`,
  `source_type`, `source_batch_no`, `created_at`, `updated_at`
)
SELECT
  q.`id`,
  COALESCE(NULLIF(TRIM(q.`business_unit_type`), ''), 'COMMERCIAL'),
  q.`rate_year`,
  CASE
    WHEN mm.`main_category_code` LIKE '11%' THEN TRIM(q.`product_code`)
    ELSE NULLIF(TRIM(mm.`bare_code`), '')
  END AS `bare_product_code`,
  NULLIF(TRIM(q.`product_name`), ''),
  NULLIF(TRIM(q.`product_spec`), ''),
  NULLIF(TRIM(q.`product_model`), ''),
  NULLIF(TRIM(q.`business_division`), ''),
  NULLIF(TRIM(q.`product_category`), ''),
  NULLIF(TRIM(q.`product_subcategory`), ''),
  q.`loss_rate`,
  NULLIF(TRIM(q.`remark`), ''),
  COALESCE(NULLIF(TRIM(q.`source_type`), ''), 'MANUAL'),
  NULLIF(TRIM(q.`source_batch_no`), ''),
  q.`created_at`,
  q.`updated_at`
FROM `lp_quality_loss_rate` q
JOIN `lp_material_master_raw` mm
  ON mm.`active_flag` = 1
 AND mm.`material_code` = q.`product_code`
 AND mm.`organization_code` =
      CASE WHEN q.`business_division` LIKE '%板换%' THEN 'PLATE' ELSE 'COMMERCIAL' END
WHERE q.`rate_year` IS NOT NULL
  AND q.`loss_rate` IS NOT NULL
  AND q.`loss_rate` >= 0
  AND q.`loss_rate` < 1
  AND q.`product_code` IS NOT NULL
  AND TRIM(q.`product_code`) <> ''
  AND (
    mm.`main_category_code` LIKE '11%'
    OR (mm.`bare_code` IS NOT NULL AND TRIM(mm.`bare_code`) <> '')
  )
ORDER BY q.`id`;

DROP TABLE `lp_quality_loss_rate`;
RENAME TABLE `lp_quality_loss_rate_v241` TO `lp_quality_loss_rate`;
