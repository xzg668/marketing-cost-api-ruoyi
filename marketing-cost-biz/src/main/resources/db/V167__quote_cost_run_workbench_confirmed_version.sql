-- =============================================================================
-- V167: 单产品成本核算工作台确认版本引用
-- -----------------------------------------------------------------------------
-- QWB-07：产品行记录当前默认引用的已确认成本版本。
-- =============================================================================

SET NAMES utf8mb4;

DELIMITER $$

DROP PROCEDURE IF EXISTS v167_add_column_if_not_exists $$
CREATE PROCEDURE v167_add_column_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_column_name VARCHAR(64),
  IN p_column_def TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
      FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND COLUMN_NAME = p_column_name
  ) THEN
    SET @sql = CONCAT('ALTER TABLE ', p_table_name, ' ADD COLUMN ', p_column_name, ' ', p_column_def);
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

DROP PROCEDURE IF EXISTS v167_add_index_if_not_exists $$
CREATE PROCEDURE v167_add_index_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_index_name VARCHAR(64),
  IN p_index_def TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
      FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND INDEX_NAME = p_index_name
  ) THEN
    SET @sql = CONCAT('ALTER TABLE ', p_table_name, ' ADD ', p_index_def);
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

DELIMITER ;

CALL v167_add_column_if_not_exists(
  'oa_form_item',
  'confirmed_cost_version_id',
  'BIGINT DEFAULT NULL COMMENT ''当前确认成本版本ID'' AFTER calc_at'
);

CALL v167_add_index_if_not_exists(
  'oa_form_item',
  'idx_oa_form_item_confirmed_cost_version',
  'KEY idx_oa_form_item_confirmed_cost_version (confirmed_cost_version_id)'
);

DROP PROCEDURE IF EXISTS v167_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v167_add_index_if_not_exists;
