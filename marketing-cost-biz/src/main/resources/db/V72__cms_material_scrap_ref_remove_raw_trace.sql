-- =============================================================================
-- V72: 收缩 CMS 回收废料映射模型
-- -----------------------------------------------------------------------------
-- 业务页只保留当前有效映射；原始行、导入记录、缺价和多废料诊断能力不再维护。
-- 已经执行过 V69 的开发库通过本迁移清理历史 raw 表和 source_raw_id 字段。
-- =============================================================================

DROP PROCEDURE IF EXISTS _cms_material_scrap_drop_column_if_exists;
DROP PROCEDURE IF EXISTS _cms_material_scrap_drop_index_if_exists;

DELIMITER //

CREATE PROCEDURE _cms_material_scrap_drop_column_if_exists(
  IN p_table_name VARCHAR(64),
  IN p_column_name VARCHAR(64)
)
BEGIN
  IF EXISTS (
    SELECT 1
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = p_table_name
      AND COLUMN_NAME = p_column_name
  ) THEN
    SET @cms_material_scrap_drop_column_sql =
      CONCAT('ALTER TABLE ', p_table_name, ' DROP COLUMN ', p_column_name);
    PREPARE cms_material_scrap_drop_column_stmt FROM @cms_material_scrap_drop_column_sql;
    EXECUTE cms_material_scrap_drop_column_stmt;
    DEALLOCATE PREPARE cms_material_scrap_drop_column_stmt;
  END IF;
END //

CREATE PROCEDURE _cms_material_scrap_drop_index_if_exists(
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
    SET @cms_material_scrap_drop_index_sql =
      CONCAT('ALTER TABLE ', p_table_name, ' DROP INDEX ', p_index_name);
    PREPARE cms_material_scrap_drop_index_stmt FROM @cms_material_scrap_drop_index_sql;
    EXECUTE cms_material_scrap_drop_index_stmt;
    DEALLOCATE PREPARE cms_material_scrap_drop_index_stmt;
  END IF;
END //

DELIMITER ;

CALL _cms_material_scrap_drop_index_if_exists(
  'lp_material_scrap_ref',
  'idx_material_scrap_ref_source_raw'
);

CALL _cms_material_scrap_drop_column_if_exists(
  'lp_material_scrap_ref',
  'source_raw_id'
);

DROP TABLE IF EXISTS cms_material_scrap_ref_raw;

DROP PROCEDURE IF EXISTS _cms_material_scrap_drop_column_if_exists;
DROP PROCEDURE IF EXISTS _cms_material_scrap_drop_index_if_exists;
