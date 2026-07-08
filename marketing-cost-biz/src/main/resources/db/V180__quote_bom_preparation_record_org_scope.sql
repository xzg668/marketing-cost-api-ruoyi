-- =============================================================================
-- V180  报价 BOM 准备记录补组织维度                              2026-07-07
-- -----------------------------------------------------------------------------
-- 目标：
--   1. lp_quote_bom_preparation_record 保存 BOM 组织和主档组织。
--   2. 月度锁定复用按 210/COMMERCIAL、220/PLATE 隔离，避免商用/板换串复用。
--
-- 注意：
--   历史准备记录不做默认组织回填；缺组织的旧记录不能参与组织化复用。
-- =============================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS v180_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v180_modify_column_if_exists;
DROP PROCEDURE IF EXISTS v180_add_index_if_not_exists;

DELIMITER //

CREATE PROCEDURE v180_add_column_if_not_exists(
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
    SET @v180_add_column_sql = CONCAT('ALTER TABLE `', p_table_name, '` ADD COLUMN ', p_column_ddl);
    PREPARE v180_add_column_stmt FROM @v180_add_column_sql;
    EXECUTE v180_add_column_stmt;
    DEALLOCATE PREPARE v180_add_column_stmt;
  END IF;
END //

CREATE PROCEDURE v180_modify_column_if_exists(
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
    SET @v180_modify_column_sql = CONCAT('ALTER TABLE `', p_table_name, '` MODIFY COLUMN ', p_column_ddl);
    PREPARE v180_modify_column_stmt FROM @v180_modify_column_sql;
    EXECUTE v180_modify_column_stmt;
    DEALLOCATE PREPARE v180_modify_column_stmt;
  END IF;
END //

CREATE PROCEDURE v180_add_index_if_not_exists(
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
    SET @v180_add_index_sql = CONCAT('ALTER TABLE `', p_table_name, '` ADD ', p_index_ddl);
    PREPARE v180_add_index_stmt FROM @v180_add_index_sql;
    EXECUTE v180_add_index_stmt;
    DEALLOCATE PREPARE v180_add_index_stmt;
  END IF;
END //

DELIMITER ;

CALL v180_add_column_if_not_exists(
  'lp_quote_bom_preparation_record',
  'price_org_code',
  'price_org_code VARCHAR(32) DEFAULT NULL COMMENT ''U9报价组织：210=商用，220=板换'' AFTER quote_product_code'
);

CALL v180_add_column_if_not_exists(
  'lp_quote_bom_preparation_record',
  'material_organization_code',
  'material_organization_code VARCHAR(32) DEFAULT NULL COMMENT ''U9料品主档组织：COMMERCIAL=商用，PLATE=板换'' AFTER price_org_code'
);

CALL v180_modify_column_if_exists(
  'lp_quote_bom_preparation_record',
  'price_org_code',
  'price_org_code VARCHAR(32) DEFAULT NULL COMMENT ''U9报价组织：210=商用，220=板换'' AFTER quote_product_code'
);

CALL v180_modify_column_if_exists(
  'lp_quote_bom_preparation_record',
  'material_organization_code',
  'material_organization_code VARCHAR(32) DEFAULT NULL COMMENT ''U9料品主档组织：COMMERCIAL=商用，PLATE=板换'' AFTER price_org_code'
);

CALL v180_add_index_if_not_exists(
  'lp_quote_bom_preparation_record',
  'idx_qbp_record_org_month_lock',
  'KEY idx_qbp_record_org_month_lock (quote_product_code, cost_period_month, price_org_code, material_organization_code, active_flag, preparation_status)'
);

DROP PROCEDURE IF EXISTS v180_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v180_modify_column_if_exists;
DROP PROCEDURE IF EXISTS v180_add_index_if_not_exists;
