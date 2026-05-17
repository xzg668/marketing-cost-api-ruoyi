-- V83：修复历史库 lp_factor_monthly_price / change_log 缺少 V5 最近调价字段的问题。
--
-- 背景：
--   部分环境已经存在 lp_factor_monthly_price，但未完整执行 V80 的幂等加列。
--   新版 FactorMonthlyPrice 实体会默认 SELECT latest_adjust_batch_id 等字段，缺列时会触发：
--     Unknown column 'latest_adjust_batch_id' in 'field list'

DROP PROCEDURE IF EXISTS add_column_if_not_exists_v83;
DROP PROCEDURE IF EXISTS add_index_if_not_exists_v83;

DELIMITER //
CREATE PROCEDURE add_column_if_not_exists_v83(
  IN p_table_name VARCHAR(128),
  IN p_column_name VARCHAR(128),
  IN p_column_def TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
  ) AND NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND COLUMN_NAME = p_column_name
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE ', p_table_name, ' ADD COLUMN ', p_column_name, ' ', p_column_def);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END//

CREATE PROCEDURE add_index_if_not_exists_v83(
  IN p_table_name VARCHAR(128),
  IN p_index_name VARCHAR(128),
  IN p_index_def TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
  ) AND NOT EXISTS (
    SELECT 1 FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND INDEX_NAME = p_index_name
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE ', p_table_name, ' ADD ', p_index_def);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END//
DELIMITER ;

CALL add_column_if_not_exists_v83(
  'lp_factor_monthly_price',
  'latest_adjust_batch_id',
  'BIGINT NULL COMMENT ''最近同步到日常报价价的调价批次'' AFTER source_upload_batch_id'
);
CALL add_column_if_not_exists_v83(
  'lp_factor_monthly_price',
  'latest_adjust_source_type',
  'VARCHAR(32) NULL COMMENT ''最近调价来源：ADJUST_EXCEL_IMPORT/MANUAL_ADJUST'' AFTER latest_adjust_batch_id'
);
CALL add_column_if_not_exists_v83(
  'lp_factor_monthly_price',
  'latest_adjusted_by',
  'VARCHAR(64) NULL COMMENT ''最近调价人'' AFTER latest_adjust_source_type'
);
CALL add_column_if_not_exists_v83(
  'lp_factor_monthly_price',
  'latest_adjusted_at',
  'DATETIME NULL COMMENT ''最近调价时间'' AFTER latest_adjusted_by'
);
CALL add_column_if_not_exists_v83(
  'lp_factor_monthly_price',
  'source_tag',
  'VARCHAR(32) NULL COMMENT ''当前日常报价价来源标签：EXCEL_IMPORT/MANUAL_ADJUST/ADJUST_IMPORT'' AFTER latest_adjusted_at'
);

CALL add_column_if_not_exists_v83(
  'lp_factor_monthly_price_change_log',
  'adjust_batch_id',
  'BIGINT NULL COMMENT ''关联 lp_factor_adjust_batch，仅同步为日常报价价时填写'' AFTER source_upload_batch_id'
);
CALL add_column_if_not_exists_v83(
  'lp_factor_monthly_price_change_log',
  'source_type',
  'VARCHAR(32) NULL COMMENT ''EXCEL_IMPORT/MANUAL_ADJUST/ADJUST_IMPORT'' AFTER adjust_batch_id'
);

CALL add_index_if_not_exists_v83(
  'lp_factor_monthly_price',
  'idx_factor_monthly_latest_adjust',
  'KEY idx_factor_monthly_latest_adjust (latest_adjust_batch_id)'
);
CALL add_index_if_not_exists_v83(
  'lp_factor_monthly_price',
  'idx_factor_monthly_source_tag',
  'KEY idx_factor_monthly_source_tag (source_tag)'
);
CALL add_index_if_not_exists_v83(
  'lp_factor_monthly_price_change_log',
  'idx_factor_price_log_adjust_batch',
  'KEY idx_factor_price_log_adjust_batch (adjust_batch_id)'
);

DROP PROCEDURE IF EXISTS add_index_if_not_exists_v83;
DROP PROCEDURE IF EXISTS add_column_if_not_exists_v83;
