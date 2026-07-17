-- =============================================================================
-- V185: 报价价格准备统一取价时点并保留历史快照
-- -----------------------------------------------------------------------------
-- 1. 每次价格准备批次固化一个 price_as_of_time，所有价格源统一使用。
-- 2. 旧明细/缺口不再删除，以 current_flag 区分当前快照和历史快照。
-- 3. 报价联动价按 price_as_of_time 分版本，避免新准备覆盖已核算引用的旧结果。
-- =============================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS v185_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v185_drop_index_if_exists;
DROP PROCEDURE IF EXISTS v185_add_index_if_not_exists;

DELIMITER $$

CREATE PROCEDURE v185_add_column_if_not_exists(
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
    SET @ddl = CONCAT(
      'ALTER TABLE `', p_table_name, '` ADD COLUMN `', p_column_name, '` ', p_column_definition);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END$$

CREATE PROCEDURE v185_drop_index_if_exists(
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

CREATE PROCEDURE v185_add_index_if_not_exists(
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

CALL v185_add_column_if_not_exists(
  'lp_price_prepare_batch',
  'price_as_of_time',
  'DATETIME NULL COMMENT ''本批次所有价格源统一取价时点'' AFTER `gap_count`'
);

CALL v185_add_column_if_not_exists(
  'lp_price_prepare_batch',
  'price_as_of_source',
  'VARCHAR(32) NOT NULL DEFAULT ''LEGACY'' COMMENT ''取价时点来源：CURRENT_TIME/REQUEST/LEGACY'' AFTER `price_as_of_time`'
);

UPDATE lp_price_prepare_batch
   SET price_as_of_time = COALESCE(price_as_of_time, started_at, created_at, updated_at, NOW()),
       price_as_of_source = COALESCE(NULLIF(price_as_of_source, ''), 'LEGACY')
 WHERE price_as_of_time IS NULL
    OR price_as_of_source IS NULL
    OR price_as_of_source = '';

ALTER TABLE lp_price_prepare_batch
  MODIFY COLUMN price_as_of_time DATETIME NOT NULL COMMENT '本批次所有价格源统一取价时点',
  MODIFY COLUMN price_as_of_source VARCHAR(32) NOT NULL DEFAULT 'LEGACY'
    COMMENT '取价时点来源：CURRENT_TIME/REQUEST/LEGACY';

CALL v185_add_column_if_not_exists(
  'lp_price_prepare_item',
  'current_flag',
  'TINYINT(1) NOT NULL DEFAULT 1 COMMENT ''1=当前快照，0=历史快照'' AFTER `message`'
);

CALL v185_add_column_if_not_exists(
  'lp_price_prepare_gap',
  'current_flag',
  'TINYINT(1) NOT NULL DEFAULT 1 COMMENT ''1=当前缺口，0=历史缺口'' AFTER `oa_push_status`'
);

UPDATE lp_price_prepare_item SET current_flag = 1 WHERE current_flag IS NULL;
UPDATE lp_price_prepare_gap SET current_flag = 1 WHERE current_flag IS NULL;

-- 原“当前结果”唯一键会迫使新批次覆盖旧明细；改为按批次保存快照。
CALL v185_drop_index_if_exists('lp_price_prepare_item', 'uk_price_prepare_item_current');
CALL v185_drop_index_if_exists('lp_price_prepare_gap', 'uk_price_prepare_gap_current');

CALL v185_add_index_if_not_exists(
  'lp_price_prepare_item',
  'uk_price_prepare_item_batch',
  'UNIQUE KEY uk_price_prepare_item_batch (prepare_no, oa_form_item_id, top_product_code, material_code)'
);

CALL v185_add_index_if_not_exists(
  'lp_price_prepare_gap',
  'uk_price_prepare_gap_batch',
  'UNIQUE KEY uk_price_prepare_gap_batch (prepare_no, oa_form_item_id, top_product_code, material_code, gap_material_code, gap_type, item_type)'
);

CALL v185_add_index_if_not_exists(
  'lp_price_prepare_item',
  'idx_price_prepare_item_current_scope',
  'KEY idx_price_prepare_item_current_scope (current_flag, oa_no, oa_form_item_id, period_month, top_product_code)'
);

CALL v185_add_index_if_not_exists(
  'lp_price_prepare_gap',
  'idx_price_prepare_gap_current_scope',
  'KEY idx_price_prepare_gap_current_scope (current_flag, oa_no, oa_form_item_id, period_month, top_product_code)'
);

CALL v185_add_index_if_not_exists(
  'lp_price_prepare_batch',
  'idx_price_prepare_as_of',
  'KEY idx_price_prepare_as_of (period_month, business_unit_type, price_as_of_time)'
);

CALL v185_add_column_if_not_exists(
  'lp_price_linked_calc_item',
  'price_as_of_time',
  'DATETIME NULL COMMENT ''报价联动价取价时点；NULL 为改造前兼容结果'' AFTER `calc_message`'
);

CALL v185_drop_index_if_exists('lp_price_linked_calc_item', 'uk_pl_calc_quote_scene');
CALL v185_add_index_if_not_exists(
  'lp_price_linked_calc_item',
  'uk_pl_calc_quote_scene_as_of',
  'UNIQUE KEY uk_pl_calc_quote_scene_as_of (business_unit_type, calc_scene, oa_no, item_code, pricing_month, price_as_of_time)'
);
CALL v185_add_index_if_not_exists(
  'lp_price_linked_calc_item',
  'idx_pl_calc_quote_as_of_lookup',
  'KEY idx_pl_calc_quote_as_of_lookup (calc_scene, oa_no, item_code, pricing_month, price_as_of_time)'
);

DROP PROCEDURE IF EXISTS v185_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v185_drop_index_if_exists;
DROP PROCEDURE IF EXISTS v185_add_index_if_not_exists;
