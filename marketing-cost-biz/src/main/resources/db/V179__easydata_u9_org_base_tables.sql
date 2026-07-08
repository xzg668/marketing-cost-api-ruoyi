-- =============================================================================
-- V179  EasyData U9 基础表补报价组织维度                         2026-07-07
-- -----------------------------------------------------------------------------
-- 目标：
--   1. lp_bom_u9_source / lp_bom_raw_hierarchy / lp_u9_bom_byproduct_master
--      增加 price_org_code，不对历史空组织做默认回填。
--   2. 唯一键纳入 price_org_code，允许同一 U9 业务键在 210/220 各自存在。
--   3. 正式查询索引纳入 price_org_code，避免商用和板换串读。
--
-- 注意：
--   本迁移只改结构和索引，不清空、不重建、不导入任何 U9 数据。
-- =============================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS v179_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v179_modify_column_if_exists;
DROP PROCEDURE IF EXISTS v179_drop_index_if_exists;
DROP PROCEDURE IF EXISTS v179_add_index_if_not_exists;

DELIMITER //

CREATE PROCEDURE v179_add_column_if_not_exists(
  IN p_table_name VARCHAR(128),
  IN p_column_name VARCHAR(128),
  IN p_column_ddl TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1
      FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
  ) AND NOT EXISTS (
    SELECT 1
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND COLUMN_NAME = p_column_name
  ) THEN
    SET @v179_add_column_sql = CONCAT('ALTER TABLE `', p_table_name, '` ADD COLUMN ', p_column_ddl);
    PREPARE v179_add_column_stmt FROM @v179_add_column_sql;
    EXECUTE v179_add_column_stmt;
    DEALLOCATE PREPARE v179_add_column_stmt;
  END IF;
END //

CREATE PROCEDURE v179_drop_index_if_exists(
  IN p_table_name VARCHAR(128),
  IN p_index_name VARCHAR(128)
)
BEGIN
  IF EXISTS (
    SELECT 1
      FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND INDEX_NAME = p_index_name
  ) THEN
    SET @v179_drop_index_sql = CONCAT('ALTER TABLE `', p_table_name, '` DROP INDEX `', p_index_name, '`');
    PREPARE v179_drop_index_stmt FROM @v179_drop_index_sql;
    EXECUTE v179_drop_index_stmt;
    DEALLOCATE PREPARE v179_drop_index_stmt;
  END IF;
END //

CREATE PROCEDURE v179_modify_column_if_exists(
  IN p_table_name VARCHAR(128),
  IN p_column_name VARCHAR(128),
  IN p_column_ddl TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND COLUMN_NAME = p_column_name
  ) THEN
    SET @v179_modify_column_sql = CONCAT('ALTER TABLE `', p_table_name, '` MODIFY COLUMN ', p_column_ddl);
    PREPARE v179_modify_column_stmt FROM @v179_modify_column_sql;
    EXECUTE v179_modify_column_stmt;
    DEALLOCATE PREPARE v179_modify_column_stmt;
  END IF;
END //

CREATE PROCEDURE v179_add_index_if_not_exists(
  IN p_table_name VARCHAR(128),
  IN p_index_name VARCHAR(128),
  IN p_index_ddl TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1
      FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
  ) AND NOT EXISTS (
    SELECT 1
      FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND INDEX_NAME = p_index_name
  ) THEN
    SET @v179_add_index_sql = CONCAT('ALTER TABLE `', p_table_name, '` ADD ', p_index_ddl);
    PREPARE v179_add_index_stmt FROM @v179_add_index_sql;
    EXECUTE v179_add_index_stmt;
    DEALLOCATE PREPARE v179_add_index_stmt;
  END IF;
END //

DELIMITER ;

CALL v179_add_column_if_not_exists(
  'lp_bom_u9_source',
  'price_org_code',
  'price_org_code VARCHAR(32) DEFAULT NULL COMMENT ''U9报价组织：210=商用，220=板换'' AFTER imported_by'
);

CALL v179_add_column_if_not_exists(
  'lp_bom_raw_hierarchy',
  'price_org_code',
  'price_org_code VARCHAR(32) DEFAULT NULL COMMENT ''U9报价组织：210=商用，220=板换'' AFTER id'
);

CALL v179_add_column_if_not_exists(
  'lp_u9_bom_byproduct_master',
  'price_org_code',
  'price_org_code VARCHAR(32) DEFAULT NULL COMMENT ''U9报价组织：210=商用，220=板换'' AFTER id'
);

CALL v179_modify_column_if_exists(
  'lp_bom_u9_source',
  'price_org_code',
  'price_org_code VARCHAR(32) DEFAULT NULL COMMENT ''U9报价组织：210=商用，220=板换'' AFTER imported_by'
);

CALL v179_modify_column_if_exists(
  'lp_bom_raw_hierarchy',
  'price_org_code',
  'price_org_code VARCHAR(32) DEFAULT NULL COMMENT ''U9报价组织：210=商用，220=板换'' AFTER id'
);

CALL v179_modify_column_if_exists(
  'lp_u9_bom_byproduct_master',
  'price_org_code',
  'price_org_code VARCHAR(32) DEFAULT NULL COMMENT ''U9报价组织：210=商用，220=板换'' AFTER id'
);

CALL v179_drop_index_if_exists('lp_bom_u9_source', 'uk_batch_relation');
CALL v179_drop_index_if_exists('lp_bom_u9_source', 'uk_batch_relation_version');
CALL v179_drop_index_if_exists('lp_bom_u9_source', 'uk_u9_source_business');
CALL v179_drop_index_if_exists('lp_bom_u9_source', 'idx_parent_purpose_effective');
CALL v179_drop_index_if_exists('lp_bom_u9_source', 'idx_import_batch');

CALL v179_add_index_if_not_exists(
  'lp_bom_u9_source',
  'uk_u9_source_org_business',
  'UNIQUE KEY uk_u9_source_org_business (price_org_code, parent_material_no, child_material_no, bom_purpose, child_seq, bom_version, effective_from, effective_to)'
);

CALL v179_add_index_if_not_exists(
  'lp_bom_u9_source',
  'idx_org_parent_purpose_effective',
  'KEY idx_org_parent_purpose_effective (price_org_code, parent_material_no, bom_purpose, effective_to)'
);

CALL v179_add_index_if_not_exists(
  'lp_bom_u9_source',
  'idx_org_import_batch',
  'KEY idx_org_import_batch (price_org_code, import_batch_id)'
);

CALL v179_add_index_if_not_exists(
  'lp_bom_u9_source',
  'idx_child',
  'KEY idx_child (child_material_no)'
);

CALL v179_drop_index_if_exists('lp_bom_raw_hierarchy', 'uk_node');
CALL v179_drop_index_if_exists('lp_bom_raw_hierarchy', 'uk_node_source_line');
CALL v179_drop_index_if_exists('lp_bom_raw_hierarchy', 'idx_top_path');
CALL v179_drop_index_if_exists('lp_bom_raw_hierarchy', 'idx_top_parent');
CALL v179_drop_index_if_exists('lp_bom_raw_hierarchy', 'idx_top_material');
CALL v179_drop_index_if_exists('lp_bom_raw_hierarchy', 'idx_effective');

CALL v179_add_index_if_not_exists(
  'lp_bom_raw_hierarchy',
  'uk_node_org_source_line',
  'UNIQUE KEY uk_node_org_source_line (top_product_code, price_org_code, source_type, bom_purpose, effective_from, source_line_key)'
);

CALL v179_add_index_if_not_exists(
  'lp_bom_raw_hierarchy',
  'idx_org_top_material',
  'KEY idx_org_top_material (price_org_code, top_product_code, material_code)'
);

CALL v179_add_index_if_not_exists(
  'lp_bom_raw_hierarchy',
  'idx_org_top_parent',
  'KEY idx_org_top_parent (price_org_code, top_product_code, parent_code)'
);

CALL v179_add_index_if_not_exists(
  'lp_bom_raw_hierarchy',
  'idx_org_top_path',
  'KEY idx_org_top_path (price_org_code, top_product_code, path)'
);

CALL v179_add_index_if_not_exists(
  'lp_bom_raw_hierarchy',
  'idx_org_effective',
  'KEY idx_org_effective (price_org_code, effective_from, effective_to)'
);

CALL v179_drop_index_if_exists('lp_u9_bom_byproduct_master', 'uk_u9_bom_byproduct_natural');
CALL v179_drop_index_if_exists('lp_u9_bom_byproduct_master', 'idx_u9_bom_byproduct_parent');
CALL v179_drop_index_if_exists('lp_u9_bom_byproduct_master', 'idx_u9_bom_byproduct_effective');

CALL v179_add_index_if_not_exists(
  'lp_u9_bom_byproduct_master',
  'uk_u9_bom_byproduct_org_natural',
  'UNIQUE KEY uk_u9_bom_byproduct_org_natural (price_org_code, bom_purpose, parent_material_no, byproduct_material_no, effective_from, effective_to)'
);

CALL v179_add_index_if_not_exists(
  'lp_u9_bom_byproduct_master',
  'idx_org_parent_effective',
  'KEY idx_org_parent_effective (price_org_code, parent_material_no, bom_purpose, effective_from, effective_to)'
);

DROP PROCEDURE IF EXISTS v179_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v179_modify_column_if_exists;
DROP PROCEDURE IF EXISTS v179_drop_index_if_exists;
DROP PROCEDURE IF EXISTS v179_add_index_if_not_exists;
