-- =============================================================================
-- V191: 财务 Cu 价格准备中间结果隔离
-- -----------------------------------------------------------------------------
-- 不新增业务表：只在既有联动价、制造件中间结果上补场景维度，保证财务重算不覆盖 OA。
-- =============================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS v191_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v191_drop_index_if_exists;
DROP PROCEDURE IF EXISTS v191_add_index_if_not_exists;

DELIMITER $$

CREATE PROCEDURE v191_add_column_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_column_name VARCHAR(64),
  IN p_column_definition TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND COLUMN_NAME = p_column_name
  ) THEN
    SET @ddl = CONCAT(
      'ALTER TABLE `', p_table_name, '` ADD COLUMN `', p_column_name, '` ',
      p_column_definition);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END$$

CREATE PROCEDURE v191_drop_index_if_exists(
  IN p_table_name VARCHAR(64),
  IN p_index_name VARCHAR(64)
)
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.STATISTICS
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

CREATE PROCEDURE v191_add_index_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_index_name VARCHAR(64),
  IN p_index_definition TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.STATISTICS
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

CALL v191_add_column_if_not_exists(
  'lp_make_part_price_calc_row',
  'price_scenario_type',
  'VARCHAR(32) NOT NULL DEFAULT ''OA_LOCKED'' COMMENT ''价格场景：OA_LOCKED/FINANCE_QUOTE_BASE'' AFTER `price_as_of_time`'
);

UPDATE lp_make_part_price_calc_row
   SET price_scenario_type = 'OA_LOCKED'
 WHERE price_scenario_type IS NULL
    OR TRIM(price_scenario_type) = '';

ALTER TABLE lp_make_part_price_calc_row
  MODIFY COLUMN price_scenario_type VARCHAR(32) NOT NULL DEFAULT 'OA_LOCKED'
    COMMENT '价格场景：OA_LOCKED/FINANCE_QUOTE_BASE';

CALL v191_drop_index_if_exists(
  'lp_make_part_price_calc_row', 'uk_make_part_price_current_as_of');
CALL v191_add_index_if_not_exists(
  'lp_make_part_price_calc_row',
  'uk_make_part_price_current_as_of_scene',
  'UNIQUE KEY uk_make_part_price_current_as_of_scene (oa_no, pricing_month, price_as_of_time, price_scenario_type, parent_material_no, child_material_no, scrap_code)'
);
CALL v191_drop_index_if_exists(
  'lp_make_part_price_calc_row', 'idx_make_part_price_as_of_lookup');
CALL v191_add_index_if_not_exists(
  'lp_make_part_price_calc_row',
  'idx_make_part_price_as_of_scene_lookup',
  'KEY idx_make_part_price_as_of_scene_lookup (parent_material_no, oa_no, business_unit_type, pricing_month, price_as_of_time, price_scenario_type)'
);

-- 报价联动价同样按 factor_source 隔离；OA 与财务场景可在同一 OA/月/时点并存。
UPDATE lp_price_linked_calc_item
   SET factor_source = 'OA_LOCKED'
 WHERE calc_scene = 'QUOTE'
   AND (factor_source IS NULL OR TRIM(factor_source) = '');

CALL v191_drop_index_if_exists(
  'lp_price_linked_calc_item', 'uk_pl_calc_quote_scene_as_of');
CALL v191_add_index_if_not_exists(
  'lp_price_linked_calc_item',
  'uk_pl_calc_quote_scene_as_of_factor',
  'UNIQUE KEY uk_pl_calc_quote_scene_as_of_factor (business_unit_type, calc_scene, factor_source, oa_no, item_code, pricing_month, price_as_of_time)'
);
CALL v191_drop_index_if_exists(
  'lp_price_linked_calc_item', 'idx_pl_calc_quote_as_of_lookup');
CALL v191_add_index_if_not_exists(
  'lp_price_linked_calc_item',
  'idx_pl_calc_quote_as_of_factor_lookup',
  'KEY idx_pl_calc_quote_as_of_factor_lookup (calc_scene, factor_source, oa_no, item_code, pricing_month, price_as_of_time)'
);

DROP PROCEDURE IF EXISTS v191_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v191_drop_index_if_exists;
DROP PROCEDURE IF EXISTS v191_add_index_if_not_exists;
