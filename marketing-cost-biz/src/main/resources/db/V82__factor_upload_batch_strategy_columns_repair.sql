-- V82：修复历史库 lp_factor_upload_batch 缺少 V5 导入用途/生效策略列的问题。
--
-- 背景：
--   部分环境已经存在 lp_factor_upload_batch，但缺少 import_purpose / effective_strategy。
--   新版 FactorUploadBatch 实体会默认 SELECT 这两个字段，缺列时会触发：
--     Unknown column 'import_purpose' in 'field list'
--
-- 约束：
--   1) 幂等执行，已存在列或索引时不重复添加。
--   2) 不修改历史数据；历史批次允许这两个字段为空。

DROP PROCEDURE IF EXISTS add_column_if_not_exists_v82;
DROP PROCEDURE IF EXISTS add_index_if_not_exists_v82;

DELIMITER //
CREATE PROCEDURE add_column_if_not_exists_v82(
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

CREATE PROCEDURE add_index_if_not_exists_v82(
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

CALL add_column_if_not_exists_v82(
  'lp_factor_upload_batch',
  'import_purpose',
  'VARCHAR(32) NULL COMMENT ''MONTHLY_LINKED_FACTOR/LINKED_APPEND_ONLY/LINKED_OVERRIDE_EFFECTIVE/MONTHLY_ADJUST'' AFTER import_type'
);
CALL add_column_if_not_exists_v82(
  'lp_factor_upload_batch',
  'effective_strategy',
  'VARCHAR(32) NULL COMMENT ''APPEND_ONLY/OVERRIDE_EFFECTIVE，联动价与影响因素导入生效策略'' AFTER import_purpose'
);

CALL add_index_if_not_exists_v82(
  'lp_factor_upload_batch',
  'idx_factor_upload_purpose_strategy',
  'KEY idx_factor_upload_purpose_strategy (import_purpose, effective_strategy)'
);

DROP PROCEDURE IF EXISTS add_index_if_not_exists_v82;
DROP PROCEDURE IF EXISTS add_column_if_not_exists_v82;
