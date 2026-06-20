-- =============================================================================
-- V169  U9 BOM 源数据唯一键改为业务口径                         2026-06-16
-- -----------------------------------------------------------------------------
-- Excel 临时导入和未来 U9 系统接入都应表达“当前 BOM 主数据快照”，不能按技术
-- import_batch_id 越导越多。同一业务 BOM 子项用以下字段唯一识别：
--   母件料号 + 子件料号 + BOM生产目的 + 子件项次 + 版本号 + 生效日期 + 失效日期
-- =============================================================================

DROP PROCEDURE IF EXISTS v169_drop_index_if_exists;
DELIMITER //
CREATE PROCEDURE v169_drop_index_if_exists(
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

DROP PROCEDURE IF EXISTS v169_add_index_if_not_exists;
DELIMITER //
CREATE PROCEDURE v169_add_index_if_not_exists(
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

CALL v169_drop_index_if_exists('lp_bom_u9_source', 'uk_batch_relation');
CALL v169_drop_index_if_exists('lp_bom_u9_source', 'uk_batch_relation_version');

DELETE old_row
  FROM lp_bom_u9_source old_row
  JOIN lp_bom_u9_source new_row
    ON old_row.parent_material_no = new_row.parent_material_no
   AND old_row.child_material_no = new_row.child_material_no
   AND old_row.bom_purpose <=> new_row.bom_purpose
   AND old_row.child_seq <=> new_row.child_seq
   AND old_row.bom_version <=> new_row.bom_version
   AND old_row.effective_from <=> new_row.effective_from
   AND old_row.effective_to <=> new_row.effective_to
   AND old_row.id < new_row.id;

CALL v169_add_index_if_not_exists(
  'lp_bom_u9_source',
  'uk_u9_source_business',
  'UNIQUE KEY uk_u9_source_business (parent_material_no, child_material_no, bom_purpose, child_seq, bom_version, effective_from, effective_to)'
);

DROP PROCEDURE IF EXISTS v169_drop_index_if_exists;
DROP PROCEDURE IF EXISTS v169_add_index_if_not_exists;
