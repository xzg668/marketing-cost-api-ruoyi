-- =============================================================================
-- V220: 产品级统一核算流水线状态
--
-- 只保存当前工作区的最近一次结构化异常，以及成功成本版本使用的完整输入指纹。
-- 失败尝试不保存成本明细；历史成功结果仍由既有成本版本表保存。
-- =============================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS v220_add_column_if_not_exists;

DELIMITER $$

CREATE PROCEDURE v220_add_column_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_column_name VARCHAR(64),
  IN p_column_def TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.TABLES
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table_name
  ) AND NOT EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table_name
       AND COLUMN_NAME = p_column_name
  ) THEN
    SET @sql = CONCAT('ALTER TABLE `', p_table_name, '` ADD COLUMN ', p_column_def);
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

DELIMITER ;

CALL v220_add_column_if_not_exists(
  'lp_quote_costing_workspace', 'last_error_step',
  '`last_error_step` VARCHAR(32) DEFAULT NULL COMMENT ''最近失败步骤'' AFTER `stale_reason_code`'
);
CALL v220_add_column_if_not_exists(
  'lp_quote_costing_workspace', 'last_error_code',
  '`last_error_code` VARCHAR(64) DEFAULT NULL COMMENT ''最近结构化错误码'' AFTER `last_error_step`'
);
CALL v220_add_column_if_not_exists(
  'lp_quote_costing_workspace', 'last_error_message',
  '`last_error_message` VARCHAR(1000) DEFAULT NULL COMMENT ''最近错误摘要'' AFTER `last_error_code`'
);
CALL v220_add_column_if_not_exists(
  'lp_quote_cost_run_version', 'input_fingerprint',
  '`input_fingerprint` CHAR(64) DEFAULT NULL COMMENT ''成功版本使用的完整输入指纹'' AFTER `finance_base_price_id`'
);

DROP PROCEDURE IF EXISTS v220_add_column_if_not_exists;
