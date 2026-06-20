-- =============================================================================
-- V172  BOM raw hierarchy preserves source BOM line instances              2026-06-17
-- -----------------------------------------------------------------------------
-- 同一父件下允许出现多条同子件料号 BOM 行，例如同一子件在不同 child_seq/process_seq
-- 下各有独立用量。raw_hierarchy 旧唯一键只到 parent+child，会覆盖这些真实行。
-- 本迁移给 raw 层补来源行实例字段，并把唯一键改为 source_line_key 粒度。
-- =============================================================================

DROP PROCEDURE IF EXISTS v172_add_column_if_not_exists;
DELIMITER //
CREATE PROCEDURE v172_add_column_if_not_exists(
  IN p_table_name VARCHAR(128),
  IN p_column_name VARCHAR(128),
  IN p_column_ddl TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
      FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = p_table_name
       AND column_name = p_column_name
  ) THEN
    SET @sql = CONCAT('ALTER TABLE `', p_table_name, '` ADD COLUMN ', p_column_ddl);
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END//
DELIMITER ;

DROP PROCEDURE IF EXISTS v172_drop_index_if_exists;
DELIMITER //
CREATE PROCEDURE v172_drop_index_if_exists(
  IN p_table_name VARCHAR(128),
  IN p_index_name VARCHAR(128)
)
BEGIN
  IF EXISTS (
    SELECT 1
      FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = p_table_name
       AND index_name = p_index_name
  ) THEN
    SET @sql = CONCAT('ALTER TABLE `', p_table_name, '` DROP INDEX `', p_index_name, '`');
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END//
DELIMITER ;

DROP PROCEDURE IF EXISTS v172_add_index_if_not_exists;
DELIMITER //
CREATE PROCEDURE v172_add_index_if_not_exists(
  IN p_table_name VARCHAR(128),
  IN p_index_name VARCHAR(128),
  IN p_index_ddl TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
      FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = p_table_name
       AND index_name = p_index_name
  ) THEN
    SET @sql = CONCAT('ALTER TABLE `', p_table_name, '` ADD ', p_index_ddl);
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END//
DELIMITER ;

CALL v172_add_column_if_not_exists(
  'lp_bom_raw_hierarchy',
  'source_u9_row_id',
  'source_u9_row_id BIGINT NULL COMMENT ''来源 lp_bom_u9_source.id，用于追溯 BOM 行实例'' AFTER sort_seq'
);

CALL v172_add_column_if_not_exists(
  'lp_bom_raw_hierarchy',
  'source_line_key',
  'source_line_key VARCHAR(255) NULL COMMENT ''来源 BOM 行业务实例 key'' AFTER source_u9_row_id'
);

CALL v172_add_column_if_not_exists(
  'lp_bom_raw_hierarchy',
  'process_seq',
  'process_seq VARCHAR(16) NULL COMMENT ''U9 工序号，如 030/040'' AFTER source_line_key'
);

UPDATE lp_bom_raw_hierarchy
   SET source_line_key = CONCAT('__LEGACY__|', id)
 WHERE source_line_key IS NULL OR source_line_key = '';

ALTER TABLE lp_bom_raw_hierarchy
  MODIFY source_line_key VARCHAR(255) NOT NULL COMMENT '来源 BOM 行业务实例 key';

CALL v172_drop_index_if_exists('lp_bom_raw_hierarchy', 'uk_node');

CALL v172_add_index_if_not_exists(
  'lp_bom_raw_hierarchy',
  'uk_node_source_line',
  'UNIQUE KEY uk_node_source_line (top_product_code, source_type, bom_purpose, effective_from, source_line_key)'
);

CALL v172_add_index_if_not_exists(
  'lp_bom_raw_hierarchy',
  'idx_source_u9_row',
  'KEY idx_source_u9_row (source_u9_row_id)'
);

DROP PROCEDURE IF EXISTS v172_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v172_drop_index_if_exists;
DROP PROCEDURE IF EXISTS v172_add_index_if_not_exists;
