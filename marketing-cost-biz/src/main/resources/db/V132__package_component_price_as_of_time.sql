-- =============================================================================
-- V132: 包装组件价格固化取价时点
-- -----------------------------------------------------------------------------
-- 说明：
--   1. 月度调价包装组件价格由子件价格汇总而来，必须保留批次 price_as_of_time。
--   2. 旧数据无法还原真实取价时点，按 generated_at/created_at 回填，仅作为历史追溯。
--   3. 原唯一键只按包装父料号 + 价格月份 + 顶层产品，会导致不同取价时点互相覆盖；
--      月度场景扩展为包含 price_as_of_time，普通价格准备仍由代码复用最新当前结果。
-- =============================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS v132_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v132_drop_index_if_exists;
DROP PROCEDURE IF EXISTS v132_add_index_if_not_exists;

DELIMITER $$

CREATE PROCEDURE v132_add_column_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_column_name VARCHAR(64),
  IN p_column_definition TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND COLUMN_NAME = p_column_name
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table_name, '` ADD COLUMN `', p_column_name, '` ', p_column_definition);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END$$

CREATE PROCEDURE v132_drop_index_if_exists(
  IN p_table_name VARCHAR(64),
  IN p_index_name VARCHAR(64)
)
BEGIN
  IF EXISTS (
    SELECT 1
      FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND INDEX_NAME = p_index_name
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table_name, '` DROP INDEX `', p_index_name, '`');
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END$$

CREATE PROCEDURE v132_add_index_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_index_name VARCHAR(64),
  IN p_index_definition TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
      FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND INDEX_NAME = p_index_name
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table_name, '` ADD ', p_index_definition);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END$$

DELIMITER ;

CALL v132_add_column_if_not_exists(
  'lp_package_component_price',
  'price_as_of_time',
  'DATETIME NULL COMMENT ''取价时点；月度调价使用批次 price_as_of_time'' AFTER `source_bom_source_type`'
);

UPDATE lp_package_component_price
   SET price_as_of_time = COALESCE(price_as_of_time, generated_at, created_at, NOW())
 WHERE price_as_of_time IS NULL;

ALTER TABLE lp_package_component_price
  MODIFY COLUMN price_as_of_time DATETIME NOT NULL COMMENT '取价时点；月度调价使用批次 price_as_of_time';

UPDATE lp_package_component_price
   SET package_material_code = COALESCE(package_material_code, ''),
       period_month = COALESCE(period_month, ''),
       source_top_product_code = COALESCE(source_top_product_code, '')
 WHERE package_material_code IS NULL
    OR period_month IS NULL
    OR source_top_product_code IS NULL;

ALTER TABLE lp_package_component_price
  MODIFY package_material_code VARCHAR(64) NOT NULL DEFAULT '' COMMENT '包装组件父料号',
  MODIFY period_month VARCHAR(7) NOT NULL DEFAULT '' COMMENT '价格月份 YYYY-MM',
  MODIFY source_top_product_code VARCHAR(64) NOT NULL DEFAULT '' COMMENT '来源顶层产品料号';

CALL v132_drop_index_if_exists('lp_package_component_price', 'uk_pkg_price_month');
CALL v132_drop_index_if_exists('lp_package_component_price', 'uk_pkg_price_month_top');
CALL v132_drop_index_if_exists('lp_package_component_price', 'uk_pkg_price_oa_top');

DELETE p1 FROM lp_package_component_price p1
JOIN lp_package_component_price p2
  ON p1.package_material_code = p2.package_material_code
 AND p1.period_month = p2.period_month
 AND p1.source_top_product_code = p2.source_top_product_code
 AND p1.price_as_of_time = p2.price_as_of_time
 AND p1.id < p2.id;

CALL v132_add_index_if_not_exists(
  'lp_package_component_price',
  'uk_pkg_price_month_top_as_of',
  'UNIQUE KEY uk_pkg_price_month_top_as_of (package_material_code, period_month, source_top_product_code, price_as_of_time)'
);

CALL v132_add_index_if_not_exists(
  'lp_package_component_price',
  'idx_pkg_price_as_of_lookup',
  'KEY idx_pkg_price_as_of_lookup (period_month, source_top_product_code, package_material_code, price_as_of_time)'
);

DROP PROCEDURE IF EXISTS v132_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v132_drop_index_if_exists;
DROP PROCEDURE IF EXISTS v132_add_index_if_not_exists;
