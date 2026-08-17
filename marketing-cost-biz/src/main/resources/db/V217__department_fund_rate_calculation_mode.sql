-- 部门经费率兼容两种数据口径：
-- 1. 历史配置的 quote_ratio 是上浮前费率，继续乘 uplift_ratio；
-- 2. 新模板 H 列的 quote_ratio 已经等于 plan_rate * uplift_ratio，核算时直接使用。

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS v217_add_column_if_not_exists;

DELIMITER $$

CREATE PROCEDURE v217_add_column_if_not_exists(
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

DELIMITER ;

CALL v217_add_column_if_not_exists(
  'lp_department_fund_rate',
  'rate_calculation_mode',
  '`rate_calculation_mode` VARCHAR(32) DEFAULT NULL COMMENT ''PLAN_UPLIFT=历史报价比例再乘上浮比例；FINAL_QUOTE=报价比例已是最终费率'' AFTER `quote_ratio`'
);

UPDATE lp_department_fund_rate
   SET rate_calculation_mode = 'PLAN_UPLIFT'
 WHERE rate_calculation_mode IS NULL
    OR TRIM(rate_calculation_mode) = '';

ALTER TABLE lp_department_fund_rate
  MODIFY rate_calculation_mode VARCHAR(32) NOT NULL DEFAULT 'FINAL_QUOTE'
    COMMENT 'PLAN_UPLIFT=历史报价比例再乘上浮比例；FINAL_QUOTE=报价比例已是最终费率';

DROP PROCEDURE IF EXISTS v217_add_column_if_not_exists;
