-- =============================================================================
-- V122: 三项费用按报价单核算维度匹配
-- -----------------------------------------------------------------------------
-- 1. 扩展 lp_three_expense_rate，承载按月 + 标准组织维度的费率配置。
-- 2. 新增 lp_three_expense_dimension_mapping，维护 OA 原始值到三项费用标准值的映射。
-- =============================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS v122_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v122_add_index_if_not_exists;

DELIMITER $$

CREATE PROCEDURE v122_add_column_if_not_exists(
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

CREATE PROCEDURE v122_add_index_if_not_exists(
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

DELIMITER ;

CALL v122_add_column_if_not_exists(
  'lp_three_expense_rate',
  'period_month',
  '`period_month` VARCHAR(7) NOT NULL DEFAULT '''' COMMENT ''费率期间，YYYY-MM'' AFTER `period`'
);

CALL v122_add_column_if_not_exists(
  'lp_three_expense_rate',
  'raw_period',
  '`raw_period` VARCHAR(32) DEFAULT NULL COMMENT ''导入原始期间'' AFTER `period_month`'
);

CALL v122_add_column_if_not_exists(
  'lp_three_expense_rate',
  'standard_company',
  '`standard_company` VARCHAR(120) NOT NULL DEFAULT '''' COMMENT ''三项费用标准公司'' AFTER `raw_period`'
);

CALL v122_add_column_if_not_exists(
  'lp_three_expense_rate',
  'production_division',
  '`production_division` VARCHAR(120) NOT NULL DEFAULT '''' COMMENT ''标准生产事业部'' AFTER `standard_company`'
);

CALL v122_add_column_if_not_exists(
  'lp_three_expense_rate',
  'applicant_department',
  '`applicant_department` VARCHAR(120) NOT NULL DEFAULT '''' COMMENT ''标准申请部门'' AFTER `production_division`'
);

CALL v122_add_column_if_not_exists(
  'lp_three_expense_rate',
  'applicant_office',
  '`applicant_office` VARCHAR(120) NOT NULL DEFAULT '''' COMMENT ''标准申请处室'' AFTER `applicant_department`'
);

CALL v122_add_column_if_not_exists(
  'lp_three_expense_rate',
  'product_category',
  '`product_category` VARCHAR(120) NOT NULL DEFAULT '''' COMMENT ''产品口径'' AFTER `applicant_office`'
);

CALL v122_add_column_if_not_exists(
  'lp_three_expense_rate',
  'product_line',
  '`product_line` VARCHAR(120) NOT NULL DEFAULT '''' COMMENT ''产线/标题事业部'' AFTER `product_category`'
);

CALL v122_add_column_if_not_exists(
  'lp_three_expense_rate',
  'three_expense_total_rate',
  '`three_expense_total_rate` DECIMAL(18,6) DEFAULT NULL COMMENT ''三项费用合计'' AFTER `sales_expense_rate`'
);

CALL v122_add_column_if_not_exists(
  'lp_three_expense_rate',
  'oem_expense_rate',
  '`oem_expense_rate` DECIMAL(18,6) DEFAULT NULL COMMENT ''OEM费用率'' AFTER `three_expense_total_rate`'
);

CALL v122_add_column_if_not_exists(
  'lp_three_expense_rate',
  'source_type',
  '`source_type` VARCHAR(32) NOT NULL DEFAULT ''EXCEL_IMPORT'' COMMENT ''来源类型'' AFTER `business_unit_type`'
);

CALL v122_add_column_if_not_exists(
  'lp_three_expense_rate',
  'import_batch_no',
  '`import_batch_no` VARCHAR(64) DEFAULT NULL COMMENT ''导入批次号'' AFTER `source_type`'
);

UPDATE lp_three_expense_rate
   SET period_month = COALESCE(NULLIF(period_month, ''), period),
       raw_period = COALESCE(raw_period, period),
       standard_company = COALESCE(NULLIF(standard_company, ''), company),
       production_division = COALESCE(NULLIF(production_division, ''), business_unit),
       applicant_department = COALESCE(NULLIF(applicant_department, ''), department)
 WHERE period_month = ''
    OR standard_company = ''
    OR production_division = ''
    OR applicant_department = '';

CALL v122_add_index_if_not_exists(
  'lp_three_expense_rate',
  'idx_three_expense_lookup_q10',
  'KEY `idx_three_expense_lookup_q10` (`business_unit_type`, `period_month`, `standard_company`, `production_division`, `applicant_department`, `applicant_office`)'
);

CREATE TABLE IF NOT EXISTS lp_three_expense_dimension_mapping (
  id BIGINT NOT NULL AUTO_INCREMENT,
  business_unit_type VARCHAR(32) NOT NULL DEFAULT '' COMMENT '业务单元类型',
  dimension_type VARCHAR(32) NOT NULL COMMENT 'COMPANY/PRODUCTION_DIVISION/DEPARTMENT/OFFICE/PRODUCT_CATEGORY/PRODUCT_LINE',
  source_system VARCHAR(32) NOT NULL DEFAULT 'OA' COMMENT '来源系统',
  source_value VARCHAR(255) NOT NULL COMMENT '来源原始值',
  standard_value VARCHAR(255) NOT NULL COMMENT '三项费用配置标准值',
  enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
  priority INT NOT NULL DEFAULT 100 COMMENT '优先级，数值越小越优先',
  remark VARCHAR(500) DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_three_expense_mapping (`business_unit_type`, `dimension_type`, `source_system`, `source_value`),
  KEY idx_three_expense_mapping_lookup (`business_unit_type`, `dimension_type`, `source_system`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='三项费用维度映射';

DROP PROCEDURE IF EXISTS v122_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v122_add_index_if_not_exists;
