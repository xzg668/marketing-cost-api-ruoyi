-- =============================================================================
-- V67: CMS 科目设置字典去重键调整
-- -----------------------------------------------------------------------------
-- 科目设置导出按 一级科目编码 + 二级科目编码 + 三级科目编码 联合去重。
-- business_unit_type 只作为导入上下文字段，不参与科目字典唯一性判断。
-- =============================================================================

DELETE older
FROM cms_subject_setting_raw older
JOIN cms_subject_setting_raw newer
  ON newer.first_subject_code = older.first_subject_code
  AND newer.second_subject_code = older.second_subject_code
  AND newer.third_subject_code = older.third_subject_code
  AND newer.id > older.id;

DROP PROCEDURE IF EXISTS _cms_subject_setting_drop_index_if_exists;
DROP PROCEDURE IF EXISTS _cms_subject_setting_add_index_if_not_exists;

DELIMITER //

CREATE PROCEDURE _cms_subject_setting_drop_index_if_exists(
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
    SET @cms_subject_setting_drop_index_sql =
      CONCAT('ALTER TABLE ', p_table_name, ' DROP INDEX ', p_index_name);
    PREPARE cms_subject_setting_drop_index_stmt FROM @cms_subject_setting_drop_index_sql;
    EXECUTE cms_subject_setting_drop_index_stmt;
    DEALLOCATE PREPARE cms_subject_setting_drop_index_stmt;
  END IF;
END //

CREATE PROCEDURE _cms_subject_setting_add_index_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_index_name VARCHAR(64),
  IN p_index_definition TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = p_table_name
      AND INDEX_NAME = p_index_name
  ) THEN
    SET @cms_subject_setting_add_index_sql =
      CONCAT('ALTER TABLE ', p_table_name, ' ', p_index_definition);
    PREPARE cms_subject_setting_add_index_stmt FROM @cms_subject_setting_add_index_sql;
    EXECUTE cms_subject_setting_add_index_stmt;
    DEALLOCATE PREPARE cms_subject_setting_add_index_stmt;
  END IF;
END //

DELIMITER ;

CALL _cms_subject_setting_drop_index_if_exists(
  'cms_subject_setting_raw',
  'uk_cms_subject_setting_code'
);

CALL _cms_subject_setting_add_index_if_not_exists(
  'cms_subject_setting_raw',
  'uk_cms_subject_setting_code',
  'ADD UNIQUE KEY uk_cms_subject_setting_code (first_subject_code, second_subject_code, third_subject_code)'
);

DROP PROCEDURE IF EXISTS _cms_subject_setting_drop_index_if_exists;
DROP PROCEDURE IF EXISTS _cms_subject_setting_add_index_if_not_exists;
