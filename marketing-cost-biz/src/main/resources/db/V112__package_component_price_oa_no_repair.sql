-- =============================================================================
-- V112: 修复旧库包装组件价格表缺少 OA 字段
-- -----------------------------------------------------------------------------
-- 现象：包装组件价格页面查询 lp_package_component_price.oa_no 时报 Unknown column。
-- 原因：部分旧库已执行到 V107，但没有应用 V110 的当前结果唯一键迁移。
-- 本脚本只修复包装组件价格表，幂等补齐 oa_no 和当前唯一键。
-- =============================================================================

SET NAMES utf8mb4;

DELIMITER $$

DROP PROCEDURE IF EXISTS v112_add_column_if_not_exists $$
CREATE PROCEDURE v112_add_column_if_not_exists(
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

DROP PROCEDURE IF EXISTS v112_drop_index_if_exists $$
CREATE PROCEDURE v112_drop_index_if_exists(
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

DROP PROCEDURE IF EXISTS v112_add_index_if_not_exists $$
CREATE PROCEDURE v112_add_index_if_not_exists(
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

CALL v112_add_column_if_not_exists(
  'lp_package_component_price',
  'oa_no',
  'VARCHAR(64) NOT NULL DEFAULT '''' COMMENT ''OA单号，当前包装组件价格唯一口径'' AFTER period_month'
);

UPDATE lp_package_component_price p
LEFT JOIN lp_package_component_snapshot s ON s.id = p.snapshot_id
   SET p.oa_no = COALESCE(NULLIF(p.oa_no, ''), NULLIF(s.source_oa_no, ''), ''),
       p.source_top_product_code = COALESCE(NULLIF(p.source_top_product_code, ''), s.source_top_product_code, '')
 WHERE p.oa_no IS NULL
    OR p.oa_no = ''
    OR p.source_top_product_code IS NULL
    OR p.source_top_product_code = '';

DELETE p1 FROM lp_package_component_price p1
JOIN lp_package_component_price p2
  ON p1.package_material_code = p2.package_material_code
 AND p1.period_month = p2.period_month
 AND p1.source_top_product_code = p2.source_top_product_code
 AND p1.id < p2.id;

CALL v112_drop_index_if_exists('lp_package_component_price', 'uk_pkg_price_month');
CALL v112_drop_index_if_exists('lp_package_component_price', 'uk_pkg_price_month_top');
CALL v112_drop_index_if_exists('lp_package_component_price', 'uk_pkg_price_oa_top');

CALL v112_add_index_if_not_exists(
  'lp_package_component_price',
  'uk_pkg_price_month_top',
  'UNIQUE KEY uk_pkg_price_month_top (package_material_code, period_month, source_top_product_code)'
);

CALL v112_add_index_if_not_exists(
  'lp_package_component_price',
  'idx_pkg_price_month_top',
  'KEY idx_pkg_price_month_top (period_month, source_top_product_code)'
);

DROP PROCEDURE IF EXISTS v112_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v112_drop_index_if_exists;
DROP PROCEDURE IF EXISTS v112_add_index_if_not_exists;
