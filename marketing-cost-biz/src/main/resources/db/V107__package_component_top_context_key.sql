-- =============================================================================
-- V107: 包装组件快照/价格按来源顶层产品隔离
-- -----------------------------------------------------------------------------
-- 说明：
--   包装父料号的子件结构可以相同，但“顶层产品 -> 包装父料号”这一行的
--   母件底数/用量来自具体顶层 BOM。月度锁定必须区分来源顶层产品，避免
--   不同产品共用同一个包装父料号时串用母件底数。
-- =============================================================================

SET NAMES utf8mb4;

DELIMITER $$

DROP PROCEDURE IF EXISTS v107_add_column_if_not_exists $$
CREATE PROCEDURE v107_add_column_if_not_exists(
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

DROP PROCEDURE IF EXISTS v107_drop_index_if_exists $$
CREATE PROCEDURE v107_drop_index_if_exists(
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

DROP PROCEDURE IF EXISTS v107_add_index_if_not_exists $$
CREATE PROCEDURE v107_add_index_if_not_exists(
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

DELIMITER ;

CALL v107_add_column_if_not_exists(
  'lp_package_component_price',
  'source_top_product_code',
  'VARCHAR(64) DEFAULT NULL COMMENT ''来源BOM顶层产品料号'' AFTER period_month'
);

CALL v107_add_column_if_not_exists(
  'lp_package_component_price',
  'source_bom_purpose',
  'VARCHAR(64) DEFAULT NULL COMMENT ''来源BOM用途'' AFTER source_top_product_code'
);

CALL v107_add_column_if_not_exists(
  'lp_package_component_price',
  'source_bom_source_type',
  'VARCHAR(32) DEFAULT NULL COMMENT ''来源BOM数据源类型，如U9'' AFTER source_bom_purpose'
);

UPDATE lp_package_component_price p
JOIN lp_package_component_snapshot s ON s.id = p.snapshot_id
   SET p.source_top_product_code = COALESCE(p.source_top_product_code, s.source_top_product_code),
       p.source_bom_purpose = COALESCE(p.source_bom_purpose, s.source_bom_purpose),
       p.source_bom_source_type = COALESCE(p.source_bom_source_type, s.source_bom_source_type)
 WHERE p.snapshot_id IS NOT NULL;

CALL v107_drop_index_if_exists('lp_package_component_snapshot', 'uk_pkg_snapshot_month');
CALL v107_add_index_if_not_exists(
  'lp_package_component_snapshot',
  'uk_pkg_snapshot_month_top',
  'UNIQUE KEY uk_pkg_snapshot_month_top (package_material_code, period_month, source_top_product_code)'
);

CALL v107_drop_index_if_exists('lp_package_component_price', 'uk_pkg_price_month');
CALL v107_add_index_if_not_exists(
  'lp_package_component_price',
  'uk_pkg_price_month_top',
  'UNIQUE KEY uk_pkg_price_month_top (package_material_code, period_month, source_top_product_code)'
);

DROP PROCEDURE IF EXISTS v107_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v107_drop_index_if_exists;
DROP PROCEDURE IF EXISTS v107_add_index_if_not_exists;
