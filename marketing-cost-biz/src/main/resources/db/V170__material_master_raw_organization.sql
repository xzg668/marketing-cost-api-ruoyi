-- =============================================================================
-- V170  U9 料品主档 raw 增加组织维度                              2026-06-16
--
-- 背景：
--   商用料品主档和板换料品主档可能存在相同料号但字段不同。raw 表按
--   organization_code + material_code 保持当前态唯一，避免板换子集导入后顶掉商用数据。
-- =============================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS _v170_material_raw_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS _v170_material_raw_add_index_if_not_exists;
DROP PROCEDURE IF EXISTS _v170_material_raw_drop_index_if_exists;

DELIMITER //

CREATE PROCEDURE _v170_material_raw_add_column_if_not_exists(
  IN p_column_name VARCHAR(64),
  IN p_column_definition TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'lp_material_master_raw'
  ) AND NOT EXISTS (
    SELECT 1
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'lp_material_master_raw'
      AND COLUMN_NAME = p_column_name
  ) THEN
    SET @v170_add_column_sql = CONCAT('ALTER TABLE lp_material_master_raw ', p_column_definition);
    PREPARE v170_add_column_stmt FROM @v170_add_column_sql;
    EXECUTE v170_add_column_stmt;
    DEALLOCATE PREPARE v170_add_column_stmt;
  END IF;
END //

CREATE PROCEDURE _v170_material_raw_add_index_if_not_exists(
  IN p_index_name VARCHAR(64),
  IN p_index_definition TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'lp_material_master_raw'
  ) AND NOT EXISTS (
    SELECT 1
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'lp_material_master_raw'
      AND INDEX_NAME = p_index_name
  ) THEN
    SET @v170_add_index_sql = CONCAT('ALTER TABLE lp_material_master_raw ', p_index_definition);
    PREPARE v170_add_index_stmt FROM @v170_add_index_sql;
    EXECUTE v170_add_index_stmt;
    DEALLOCATE PREPARE v170_add_index_stmt;
  END IF;
END //

CREATE PROCEDURE _v170_material_raw_drop_index_if_exists(
  IN p_index_name VARCHAR(64)
)
BEGIN
  IF EXISTS (
    SELECT 1
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'lp_material_master_raw'
      AND INDEX_NAME = p_index_name
  ) THEN
    SET @v170_drop_index_sql = CONCAT('ALTER TABLE lp_material_master_raw DROP INDEX ', p_index_name);
    PREPARE v170_drop_index_stmt FROM @v170_drop_index_sql;
    EXECUTE v170_drop_index_stmt;
    DEALLOCATE PREPARE v170_drop_index_stmt;
  END IF;
END //

DELIMITER ;

CALL _v170_material_raw_add_column_if_not_exists(
  'organization_code',
  'ADD COLUMN organization_code VARCHAR(32) NOT NULL DEFAULT ''COMMERCIAL'' COMMENT ''料品组织：COMMERCIAL=商用，PLATE=板换'' AFTER material_code'
);

UPDATE lp_material_master_raw
SET organization_code = 'COMMERCIAL'
WHERE organization_code IS NULL OR organization_code = '';

CALL _v170_material_raw_drop_index_if_exists('uk_raw_material_batch');
CALL _v170_material_raw_drop_index_if_exists('uk_raw_org_material_batch');
CALL _v170_material_raw_add_index_if_not_exists(
  'uk_raw_org_material',
  'ADD UNIQUE KEY uk_raw_org_material (organization_code, material_code)'
);
CALL _v170_material_raw_add_index_if_not_exists(
  'idx_raw_org_batch',
  'ADD KEY idx_raw_org_batch (organization_code, import_batch_id)'
);
CALL _v170_material_raw_add_index_if_not_exists(
  'idx_raw_org_material',
  'ADD KEY idx_raw_org_material (organization_code, material_code)'
);

DROP PROCEDURE IF EXISTS _v170_material_raw_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS _v170_material_raw_add_index_if_not_exists;
DROP PROCEDURE IF EXISTS _v170_material_raw_drop_index_if_exists;
