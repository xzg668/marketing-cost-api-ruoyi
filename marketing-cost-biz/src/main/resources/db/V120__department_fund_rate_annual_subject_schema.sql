-- =============================================================================
-- V120: 部门经费率配置表改为年度 + 事业部 + 费用科目明细口径
-- -----------------------------------------------------------------------------
-- 业务口径：
--   1. 部门经费率配置仍使用 lp_department_fund_rate，不另建表。
--   2. 导入模板按费用科目明细落库，一行费用科目一条配置。
--   3. 成本核算按“报价产品料号 -> U9 主档 production_division -> 年度+事业部+费用科目”
--      取 quote_ratio（报价比例，元/分钟）。
-- =============================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS v120_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v120_add_index_if_not_exists;
DROP PROCEDURE IF EXISTS v120_drop_index_if_exists;

DELIMITER $$

CREATE PROCEDURE v120_add_column_if_not_exists(
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

CREATE PROCEDURE v120_add_index_if_not_exists(
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

CREATE PROCEDURE v120_drop_index_if_exists(
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

CALL v120_add_column_if_not_exists(
  'lp_department_fund_rate',
  'rate_year',
  '`rate_year` INT DEFAULT NULL COMMENT ''年度，如 2026'' AFTER `business_unit_type`'
);

CALL v120_add_column_if_not_exists(
  'lp_department_fund_rate',
  'business_division',
  '`business_division` VARCHAR(120) DEFAULT NULL COMMENT ''事业部，匹配 lp_material_master_raw.production_division'' AFTER `rate_year`'
);

CALL v120_add_column_if_not_exists(
  'lp_department_fund_rate',
  'expense_subject',
  '`expense_subject` VARCHAR(120) DEFAULT NULL COMMENT ''费用科目：水电费用/工装零星费用/大修费用等'' AFTER `business_division`'
);

CALL v120_add_column_if_not_exists(
  'lp_department_fund_rate',
  'budget_amount',
  '`budget_amount` DECIMAL(18,6) DEFAULT NULL COMMENT ''预算费用'' AFTER `expense_subject`'
);

CALL v120_add_column_if_not_exists(
  'lp_department_fund_rate',
  'total_work_minutes',
  '`total_work_minutes` DECIMAL(18,6) DEFAULT NULL COMMENT ''总工时/总分钟数'' AFTER `budget_amount`'
);

CALL v120_add_column_if_not_exists(
  'lp_department_fund_rate',
  'plan_rate',
  '`plan_rate` DECIMAL(18,6) DEFAULT NULL COMMENT ''计划（元/分钟）'' AFTER `total_work_minutes`'
);

CALL v120_add_column_if_not_exists(
  'lp_department_fund_rate',
  'uplift_ratio',
  '`uplift_ratio` DECIMAL(18,6) DEFAULT NULL COMMENT ''上浮比例'' AFTER `plan_rate`'
);

CALL v120_add_column_if_not_exists(
  'lp_department_fund_rate',
  'quote_ratio',
  '`quote_ratio` DECIMAL(18,6) DEFAULT NULL COMMENT ''报价比例（元/分钟），成本核算实际使用'' AFTER `uplift_ratio`'
);

CALL v120_add_column_if_not_exists(
  'lp_department_fund_rate',
  'source_type',
  '`source_type` VARCHAR(32) DEFAULT NULL COMMENT ''来源：MANUAL/EXCEL_IMPORT'' AFTER `quote_ratio`'
);

CALL v120_add_column_if_not_exists(
  'lp_department_fund_rate',
  'source_batch_no',
  '`source_batch_no` VARCHAR(128) DEFAULT NULL COMMENT ''导入批次号'' AFTER `source_type`'
);

CALL v120_add_column_if_not_exists(
  'lp_department_fund_rate',
  'remark',
  '`remark` VARCHAR(500) DEFAULT NULL COMMENT ''备注'' AFTER `source_batch_no`'
);

ALTER TABLE lp_department_fund_rate
  MODIFY business_unit VARCHAR(120) NOT NULL DEFAULT '' COMMENT '历史事业部字段，新口径同步 business_division',
  MODIFY overhaul_rate DECIMAL(18,6) NOT NULL DEFAULT 0 COMMENT '历史大修费率，新口径不参与匹配',
  MODIFY tooling_repair_rate DECIMAL(18,6) NOT NULL DEFAULT 0 COMMENT '历史工装零星修理费率，新口径不参与匹配',
  MODIFY water_power_rate DECIMAL(18,6) NOT NULL DEFAULT 0 COMMENT '历史水电费率，新口径不参与匹配',
  MODIFY other_rate DECIMAL(18,6) NOT NULL DEFAULT 0 COMMENT '历史其他费率，新口径不参与匹配',
  MODIFY uplift_rate DECIMAL(18,6) NOT NULL DEFAULT 0 COMMENT '历史上浮比率，新口径使用 uplift_ratio',
  MODIFY manhour_rate DECIMAL(18,6) NOT NULL DEFAULT 0 COMMENT '工时率，展示和部门经费金额基数换算用';

UPDATE lp_department_fund_rate
   SET business_unit_type = COALESCE(business_unit_type, 'COMMERCIAL'),
       rate_year = COALESCE(rate_year, YEAR(CURDATE())),
       business_division = COALESCE(NULLIF(business_division, ''), NULLIF(business_unit, ''), ''),
       expense_subject = COALESCE(NULLIF(expense_subject, ''), 'LEGACY'),
       quote_ratio = COALESCE(quote_ratio, overhaul_rate),
       uplift_ratio = COALESCE(uplift_ratio, uplift_rate),
       source_type = COALESCE(source_type, 'MANUAL')
 WHERE business_unit_type IS NULL
    OR rate_year IS NULL
    OR business_division IS NULL
    OR expense_subject IS NULL
    OR quote_ratio IS NULL
    OR uplift_ratio IS NULL
    OR source_type IS NULL;

CALL v120_drop_index_if_exists('lp_department_fund_rate', 'uk_dept_fund_unique');
CALL v120_drop_index_if_exists('lp_department_fund_rate', 'uk_department_fund_rate_subject');
CALL v120_add_index_if_not_exists(
  'lp_department_fund_rate',
  'uk_department_fund_rate_subject',
  'UNIQUE KEY `uk_department_fund_rate_subject` (`business_unit_type`, `rate_year`, `business_division`, `expense_subject`)'
);

CALL v120_add_index_if_not_exists(
  'lp_department_fund_rate',
  'idx_department_fund_rate_lookup',
  'KEY `idx_department_fund_rate_lookup` (`business_unit_type`, `rate_year`, `business_division`, `expense_subject`)'
);

DROP PROCEDURE IF EXISTS v120_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v120_add_index_if_not_exists;
DROP PROCEDURE IF EXISTS v120_drop_index_if_exists;
