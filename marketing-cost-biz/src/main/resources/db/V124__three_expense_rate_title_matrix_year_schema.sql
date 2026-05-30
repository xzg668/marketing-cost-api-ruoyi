-- =============================================================================
-- V124: 三项费用标题矩阵导入与年度匹配口径
-- -----------------------------------------------------------------------------
-- 业务口径：
--   1. 同一张三项费用模板里的 6 个配置区域仍落到 lp_three_expense_rate 一张表。
--   2. 配置区域标题拆成 product_category + product_line。
--   3. 成本核算按核算执行当天年份 period_year 匹配，不再按 OA 申请月份 period_month 匹配。
--   4. 申请处室为空或 "/" 时，后续成本核算按申请部门兜底匹配。
-- =============================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS v124_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v124_add_index_if_not_exists;
DROP PROCEDURE IF EXISTS v124_drop_index_if_exists;

DELIMITER $$

CREATE PROCEDURE v124_add_column_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_column_name VARCHAR(64),
  IN p_column_def TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1
      FROM INFORMATION_SCHEMA.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
  ) AND NOT EXISTS (
    SELECT 1
      FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND COLUMN_NAME = p_column_name
  ) THEN
    SET @sql = CONCAT('ALTER TABLE `', p_table_name, '` ADD COLUMN ', p_column_def);
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

CREATE PROCEDURE v124_add_index_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_index_name VARCHAR(64),
  IN p_index_def TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1
      FROM INFORMATION_SCHEMA.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
  ) AND NOT EXISTS (
    SELECT 1
      FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND INDEX_NAME = p_index_name
  ) THEN
    SET @sql = CONCAT('ALTER TABLE `', p_table_name, '` ADD ', p_index_def);
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

CREATE PROCEDURE v124_drop_index_if_exists(
  IN p_table_name VARCHAR(64),
  IN p_index_name VARCHAR(64)
)
BEGIN
  IF EXISTS (
    SELECT 1
      FROM INFORMATION_SCHEMA.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
  ) AND EXISTS (
    SELECT 1
      FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND INDEX_NAME = p_index_name
  ) THEN
    SET @sql = CONCAT('ALTER TABLE `', p_table_name, '` DROP INDEX `', p_index_name, '`');
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

DELIMITER ;

CALL v124_add_column_if_not_exists(
  'lp_three_expense_rate',
  'period_year',
  '`period_year` INT NOT NULL DEFAULT 0 COMMENT ''费率年度，YYYY，如2026'' AFTER `period_month`'
);

CALL v124_add_column_if_not_exists(
  'lp_three_expense_rate',
  'product_category',
  '`product_category` VARCHAR(120) NOT NULL DEFAULT '''' COMMENT ''标题拆分字段：产品口径，如商用直销产品'' AFTER `applicant_office`'
);

CALL v124_add_column_if_not_exists(
  'lp_three_expense_rate',
  'product_line',
  '`product_line` VARCHAR(120) NOT NULL DEFAULT '''' COMMENT ''标题拆分字段：产线/标题事业部，如国内产线'' AFTER `product_category`'
);

CALL v124_add_column_if_not_exists(
  'lp_three_expense_rate',
  'applicant_department',
  '`applicant_department` VARCHAR(120) NOT NULL DEFAULT '''' COMMENT ''配置表申请部门'' AFTER `production_division`'
);

CALL v124_add_column_if_not_exists(
  'lp_three_expense_rate',
  'applicant_office',
  '`applicant_office` VARCHAR(120) NOT NULL DEFAULT '''' COMMENT ''配置表申请处室，空或/表示没有具体处室'' AFTER `applicant_department`'
);

CALL v124_add_column_if_not_exists(
  'lp_three_expense_rate',
  'three_expense_total_rate',
  '`three_expense_total_rate` DECIMAL(18,6) DEFAULT NULL COMMENT ''三项费用合计'' AFTER `sales_expense_rate`'
);

CALL v124_add_column_if_not_exists(
  'lp_three_expense_rate',
  'oem_expense_rate',
  '`oem_expense_rate` DECIMAL(18,6) DEFAULT NULL COMMENT ''OEM费用率，仅导入展示，暂不参与计算'' AFTER `three_expense_total_rate`'
);

CALL v124_add_column_if_not_exists(
  'lp_three_expense_rate',
  'source_type',
  '`source_type` VARCHAR(32) NOT NULL DEFAULT ''EXCEL_IMPORT'' COMMENT ''来源类型，当前为EXCEL_IMPORT'' AFTER `business_unit_type`'
);

CALL v124_add_column_if_not_exists(
  'lp_three_expense_rate',
  'import_batch_no',
  '`import_batch_no` VARCHAR(64) DEFAULT NULL COMMENT ''导入批次号'' AFTER `source_type`'
);

ALTER TABLE lp_three_expense_rate
  MODIFY company VARCHAR(120) NOT NULL DEFAULT '' COMMENT '历史公司字段，新口径不参与匹配',
  MODIFY business_unit VARCHAR(120) NOT NULL DEFAULT '' COMMENT '历史生产事业部字段，新口径不参与匹配',
  MODIFY department VARCHAR(120) NOT NULL DEFAULT '' COMMENT '历史部门字段，新口径使用 applicant_department',
  MODIFY period VARCHAR(20) NOT NULL DEFAULT '' COMMENT '历史期间字段，新口径使用 period_year',
  MODIFY three_expense_rate_2025 DECIMAL(10,6) NOT NULL DEFAULT 0 COMMENT '历史2025三项费用，新口径不参与计算',
  MODIFY three_expense_rate_2026 DECIMAL(10,6) NOT NULL DEFAULT 0 COMMENT '历史2026三项费用，新口径不参与计算';

UPDATE lp_three_expense_rate
       SET period_year = CASE
         WHEN period_year > 0 THEN period_year
         WHEN period_month REGEXP '^[0-9]{4}' THEN CAST(LEFT(period_month, 4) AS UNSIGNED)
         WHEN period REGEXP '^[0-9]{4}' THEN CAST(LEFT(period, 4) AS UNSIGNED)
         ELSE 0
       END,
       applicant_department = COALESCE(NULLIF(applicant_department, ''), NULLIF(department, ''), ''),
       applicant_office = COALESCE(applicant_office, ''),
       product_category = COALESCE(product_category, ''),
       product_line = COALESCE(product_line, ''),
       source_type = COALESCE(NULLIF(source_type, ''), 'EXCEL_IMPORT')
 WHERE period_year = 0
    OR applicant_department IS NULL
    OR applicant_department = ''
    OR applicant_office IS NULL
    OR product_category IS NULL
    OR product_line IS NULL
    OR source_type IS NULL
    OR source_type = '';

UPDATE lp_three_expense_rate
   SET business_unit_type = 'COMMERCIAL'
 WHERE business_unit_type IS NULL
    OR business_unit_type = '';

ALTER TABLE lp_three_expense_rate
  MODIFY business_unit_type VARCHAR(20) NOT NULL DEFAULT 'COMMERCIAL' COMMENT '业务单元（租户口径）';

CALL v124_drop_index_if_exists('lp_three_expense_rate', 'uk_three_expense_unique');
CALL v124_drop_index_if_exists('lp_three_expense_rate', 'uk_three_expense_year_org');
CALL v124_add_index_if_not_exists(
  'lp_three_expense_rate',
  'uk_three_expense_year_org',
  'UNIQUE KEY `uk_three_expense_year_org` (`business_unit_type`, `period_year`, `product_category`, `product_line`, `applicant_department`, `applicant_office`)'
);

CALL v124_add_index_if_not_exists(
  'lp_three_expense_rate',
  'idx_three_expense_lookup',
  'KEY `idx_three_expense_lookup` (`business_unit_type`, `period_year`, `product_category`, `product_line`, `applicant_office`, `applicant_department`)'
);

DROP PROCEDURE IF EXISTS v124_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v124_add_index_if_not_exists;
DROP PROCEDURE IF EXISTS v124_drop_index_if_exists;
