-- =============================================================================
-- V162: 报价料号产品 BOM 核算行增加产品行维度和编辑审计字段
-- -----------------------------------------------------------------------------
-- QCB-01：lp_bom_costing_row 是报价核算用 BOM 快照和编辑对象。
-- 需要按 OA + 产品行 + 顶层料号 + 核算月隔离，避免同一 OA 下重复产品料号串用快照。
-- =============================================================================

SET NAMES utf8mb4;

DELIMITER $$

DROP PROCEDURE IF EXISTS v162_add_column_if_not_exists $$
CREATE PROCEDURE v162_add_column_if_not_exists(
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

DROP PROCEDURE IF EXISTS v162_add_index_if_not_exists $$
CREATE PROCEDURE v162_add_index_if_not_exists(
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

DROP PROCEDURE IF EXISTS v162_drop_index_if_exists $$
CREATE PROCEDURE v162_drop_index_if_exists(
  IN p_table_name VARCHAR(64),
  IN p_index_name VARCHAR(64)
)
BEGIN
  IF EXISTS (
    SELECT 1
      FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND INDEX_NAME = p_index_name
  ) THEN
    SET @sql = CONCAT('ALTER TABLE ', p_table_name, ' DROP INDEX ', p_index_name);
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

DELIMITER ;

CALL v162_add_column_if_not_exists(
  'lp_bom_costing_row',
  'oa_form_item_id',
  'BIGINT DEFAULT NULL COMMENT ''OA 产品明细行 ID'' AFTER oa_no'
);

CALL v162_add_column_if_not_exists(
  'lp_bom_costing_row',
  'unit',
  'VARCHAR(64) DEFAULT NULL COMMENT ''计量单位，报价核算 BOM 编辑字段'' AFTER material_spec'
);

CALL v162_add_column_if_not_exists(
  'lp_bom_costing_row',
  'material_attribute',
  'VARCHAR(100) DEFAULT NULL COMMENT ''材料属性，报价核算 BOM 编辑字段'' AFTER unit'
);

CALL v162_add_column_if_not_exists(
  'lp_bom_costing_row',
  'manual_modified',
  'TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''是否人工修改报价核算 BOM 行'' AFTER raw_version_effective_from'
);

CALL v162_add_column_if_not_exists(
  'lp_bom_costing_row',
  'modified_by',
  'VARCHAR(64) DEFAULT NULL COMMENT ''报价核算 BOM 行最后修改人'' AFTER manual_modified'
);

CALL v162_add_column_if_not_exists(
  'lp_bom_costing_row',
  'modified_at',
  'DATETIME DEFAULT NULL COMMENT ''报价核算 BOM 行最后修改时间'' AFTER modified_by'
);

CALL v162_add_index_if_not_exists(
  'lp_bom_costing_row',
  'idx_bom_costing_quote_item_period',
  'INDEX idx_bom_costing_quote_item_period (oa_no, oa_form_item_id, top_product_code, period_month)'
);

-- 旧唯一键缺少 oa_form_item_id，同一 OA 下相同产品料号的不同产品行会互相冲突。
CALL v162_drop_index_if_exists('lp_bom_costing_row', 'uk_oa_material_version');

CALL v162_add_index_if_not_exists(
  'lp_bom_costing_row',
  'uk_bom_costing_item_material_version',
  'UNIQUE KEY uk_bom_costing_item_material_version (oa_no, oa_form_item_id, top_product_code, material_code, as_of_date, raw_version_effective_from)'
);

DROP PROCEDURE IF EXISTS v162_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v162_add_index_if_not_exists;
DROP PROCEDURE IF EXISTS v162_drop_index_if_exists;
