-- =============================================================================
-- V168  U9 BOM 源数据唯一键改为多版本口径                         2026-06-16
-- -----------------------------------------------------------------------------
-- 背景：
--   U9 / 系统导出的 BOM 主数据里，同一 (母件, 子件, BOM生产目的, 子件项次)
--   可以因为版本号、生效日期不同而多行并存。旧唯一键只到 child_seq，会把正常多版本
--   数据误判为重复，导致 Excel 临时导入失败。
--
-- 口径：
--   import_batch_id 仍保留为 Excel 临时导入追溯字段，但不再把“父子关系”视为批次内唯一；
--   同一批次内允许不同 bom_version / effective_from 的 U9 版本并存。
-- =============================================================================

DROP PROCEDURE IF EXISTS v168_drop_index_if_exists;
DELIMITER //
CREATE PROCEDURE v168_drop_index_if_exists(
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

DROP PROCEDURE IF EXISTS v168_add_index_if_not_exists;
DELIMITER //
CREATE PROCEDURE v168_add_index_if_not_exists(
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

CALL v168_drop_index_if_exists('lp_bom_u9_source', 'uk_batch_relation');
CALL v168_add_index_if_not_exists(
  'lp_bom_u9_source',
  'uk_batch_relation_version',
  'UNIQUE KEY uk_batch_relation_version (import_batch_id, parent_material_no, child_material_no, bom_purpose, child_seq, bom_version, effective_from)'
);

DROP PROCEDURE IF EXISTS v168_drop_index_if_exists;
DROP PROCEDURE IF EXISTS v168_add_index_if_not_exists;
