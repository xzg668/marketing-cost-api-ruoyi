-- =============================================================================
-- V181  BOM 月度快照和包装组件快照/价格补组织维度                2026-07-07
-- -----------------------------------------------------------------------------
-- 目标：
--   1. lp_quote_bom_monthly_snapshot 按 price_org_code 隔离当月 BOM 复用。
--   2. lp_package_component_snapshot 按 price_org_code 隔离包装结构快照。
--   3. lp_package_component_price 按 price_org_code 隔离包装价格复用和唯一键。
--
-- 注意：
--   历史数据不做默认组织回填；缺组织的旧快照不能参与组织化复用。
-- =============================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS v181_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v181_modify_column_if_exists;
DROP PROCEDURE IF EXISTS v181_drop_index_if_exists;
DROP PROCEDURE IF EXISTS v181_add_index_if_not_exists;
DROP PROCEDURE IF EXISTS v181_execute_if_table_exists;
DROP PROCEDURE IF EXISTS v181_execute_if_tables_exist;

DELIMITER //

CREATE PROCEDURE v181_add_column_if_not_exists(
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
    SET @v181_add_column_sql = CONCAT('ALTER TABLE `', p_table_name, '` ADD COLUMN ', p_column_ddl);
    PREPARE v181_add_column_stmt FROM @v181_add_column_sql;
    EXECUTE v181_add_column_stmt;
    DEALLOCATE PREPARE v181_add_column_stmt;
  END IF;
END //

CREATE PROCEDURE v181_modify_column_if_exists(
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
    SET @v181_modify_column_sql = CONCAT('ALTER TABLE `', p_table_name, '` MODIFY COLUMN ', p_column_ddl);
    PREPARE v181_modify_column_stmt FROM @v181_modify_column_sql;
    EXECUTE v181_modify_column_stmt;
    DEALLOCATE PREPARE v181_modify_column_stmt;
  END IF;
END //

CREATE PROCEDURE v181_drop_index_if_exists(
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
    SET @v181_drop_index_sql = CONCAT('ALTER TABLE `', p_table_name, '` DROP INDEX `', p_index_name, '`');
    PREPARE v181_drop_index_stmt FROM @v181_drop_index_sql;
    EXECUTE v181_drop_index_stmt;
    DEALLOCATE PREPARE v181_drop_index_stmt;
  END IF;
END //

CREATE PROCEDURE v181_add_index_if_not_exists(
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
    SET @v181_add_index_sql = CONCAT('ALTER TABLE `', p_table_name, '` ADD ', p_index_ddl);
    PREPARE v181_add_index_stmt FROM @v181_add_index_sql;
    EXECUTE v181_add_index_stmt;
    DEALLOCATE PREPARE v181_add_index_stmt;
  END IF;
END //

CREATE PROCEDURE v181_execute_if_table_exists(
  IN p_table_name VARCHAR(128),
  IN p_sql TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1
      FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
  ) THEN
    SET @v181_execute_sql = p_sql;
    PREPARE v181_execute_stmt FROM @v181_execute_sql;
    EXECUTE v181_execute_stmt;
    DEALLOCATE PREPARE v181_execute_stmt;
  END IF;
END //

CREATE PROCEDURE v181_execute_if_tables_exist(
  IN p_table_name_a VARCHAR(128),
  IN p_table_name_b VARCHAR(128),
  IN p_sql TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1
      FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name_a
  ) AND EXISTS (
    SELECT 1
      FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name_b
  ) THEN
    SET @v181_execute_sql = p_sql;
    PREPARE v181_execute_stmt FROM @v181_execute_sql;
    EXECUTE v181_execute_stmt;
    DEALLOCATE PREPARE v181_execute_stmt;
  END IF;
END //

DELIMITER ;

CALL v181_add_column_if_not_exists(
  'lp_quote_bom_monthly_snapshot',
  'price_org_code',
  'price_org_code VARCHAR(32) DEFAULT NULL COMMENT ''U9报价组织：210=商用，220=板换'' AFTER product_code'
);

CALL v181_add_column_if_not_exists(
  'lp_package_component_snapshot',
  'price_org_code',
  'price_org_code VARCHAR(32) DEFAULT NULL COMMENT ''U9报价组织：210=商用，220=板换'' AFTER package_material_code'
);

CALL v181_add_column_if_not_exists(
  'lp_package_component_price',
  'price_org_code',
  'price_org_code VARCHAR(32) DEFAULT NULL COMMENT ''U9报价组织：210=商用，220=板换'' AFTER package_material_code'
);

CALL v181_modify_column_if_exists(
  'lp_quote_bom_monthly_snapshot',
  'price_org_code',
  'price_org_code VARCHAR(32) DEFAULT NULL COMMENT ''U9报价组织：210=商用，220=板换'' AFTER product_code'
);

CALL v181_modify_column_if_exists(
  'lp_package_component_snapshot',
  'price_org_code',
  'price_org_code VARCHAR(32) DEFAULT NULL COMMENT ''U9报价组织：210=商用，220=板换'' AFTER package_material_code'
);

CALL v181_modify_column_if_exists(
  'lp_package_component_price',
  'price_org_code',
  'price_org_code VARCHAR(32) DEFAULT NULL COMMENT ''U9报价组织：210=商用，220=板换'' AFTER package_material_code'
);

CALL v181_drop_index_if_exists('lp_quote_bom_monthly_snapshot', 'idx_quote_bom_monthly_key');
CALL v181_add_index_if_not_exists(
  'lp_quote_bom_monthly_snapshot',
  'idx_quote_bom_monthly_key',
  'KEY idx_quote_bom_monthly_key (product_code, price_org_code, customer_code, package_method, cost_period_month, active_flag)'
);

CALL v181_drop_index_if_exists('lp_package_component_snapshot', 'uk_pkg_snapshot_month');
CALL v181_drop_index_if_exists('lp_package_component_snapshot', 'uk_pkg_snapshot_month_top');
CALL v181_drop_index_if_exists('lp_package_component_snapshot', 'uk_pkg_snapshot_month_top_org');
CALL v181_add_index_if_not_exists(
  'lp_package_component_snapshot',
  'uk_pkg_snapshot_month_top_org',
  'UNIQUE KEY uk_pkg_snapshot_month_top_org (package_material_code, period_month, source_top_product_code, price_org_code)'
);

CALL v181_drop_index_if_exists('lp_package_component_price', 'uk_pkg_price_month');
CALL v181_drop_index_if_exists('lp_package_component_price', 'uk_pkg_price_month_top');
CALL v181_drop_index_if_exists('lp_package_component_price', 'uk_pkg_price_oa_top');
CALL v181_drop_index_if_exists('lp_package_component_price', 'uk_pkg_price_month_top_as_of');
CALL v181_drop_index_if_exists('lp_package_component_price', 'uk_pkg_price_month_top_org_as_of');
CALL v181_add_index_if_not_exists(
  'lp_package_component_price',
  'uk_pkg_price_month_top_org_as_of',
  'UNIQUE KEY uk_pkg_price_month_top_org_as_of (package_material_code, period_month, source_top_product_code, price_org_code, price_as_of_time)'
);

CALL v181_add_index_if_not_exists(
  'lp_package_component_price',
  'idx_pkg_price_month_top_org',
  'KEY idx_pkg_price_month_top_org (period_month, source_top_product_code, price_org_code, package_material_code)'
);

DROP PROCEDURE IF EXISTS v181_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v181_modify_column_if_exists;
DROP PROCEDURE IF EXISTS v181_drop_index_if_exists;
DROP PROCEDURE IF EXISTS v181_add_index_if_not_exists;
DROP PROCEDURE IF EXISTS v181_execute_if_table_exists;
DROP PROCEDURE IF EXISTS v181_execute_if_tables_exist;
