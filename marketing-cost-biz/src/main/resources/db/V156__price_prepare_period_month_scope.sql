-- =============================================================================
-- V156: 价格准备明细/缺口按核算月份隔离
-- -----------------------------------------------------------------------------
-- 说明：
--   1. 价格准备批次已有 period_month，但 item/gap 当前结果唯一键未带月份。
--   2. 当前月重算只消费当前核算月准备结果，避免不同月份准备状态互相覆盖。
-- =============================================================================

SET NAMES utf8mb4;

DELIMITER $$

DROP PROCEDURE IF EXISTS v156_add_column_if_not_exists $$
CREATE PROCEDURE v156_add_column_if_not_exists(
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

DROP PROCEDURE IF EXISTS v156_drop_index_if_exists $$
CREATE PROCEDURE v156_drop_index_if_exists(
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

DROP PROCEDURE IF EXISTS v156_add_index_if_not_exists $$
CREATE PROCEDURE v156_add_index_if_not_exists(
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

CALL v156_add_column_if_not_exists(
  'lp_price_prepare_item',
  'period_month',
  'VARCHAR(7) NOT NULL DEFAULT '''' COMMENT ''价格期间 YYYY-MM'' AFTER prepare_no'
);

CALL v156_add_column_if_not_exists(
  'lp_price_prepare_gap',
  'period_month',
  'VARCHAR(7) NOT NULL DEFAULT '''' COMMENT ''价格期间 YYYY-MM'' AFTER prepare_no'
);

UPDATE lp_price_prepare_item i
JOIN lp_price_prepare_batch b ON b.prepare_no = i.prepare_no
   SET i.period_month = b.period_month
 WHERE i.period_month = '';

UPDATE lp_price_prepare_gap g
JOIN lp_price_prepare_batch b ON b.prepare_no = g.prepare_no
   SET g.period_month = b.period_month
 WHERE g.period_month = '';

UPDATE lp_price_prepare_item
   SET period_month = DATE_FORMAT(CURRENT_DATE(), '%Y-%m')
 WHERE period_month = '';

UPDATE lp_price_prepare_gap
   SET period_month = DATE_FORMAT(CURRENT_DATE(), '%Y-%m')
 WHERE period_month = '';

CALL v156_drop_index_if_exists('lp_price_prepare_item', 'uk_price_prepare_item_current');
CALL v156_drop_index_if_exists('lp_price_prepare_gap', 'uk_price_prepare_gap_current');

DELETE i1 FROM lp_price_prepare_item i1
JOIN lp_price_prepare_item i2
  ON i1.oa_no = i2.oa_no
 AND i1.period_month = i2.period_month
 AND i1.top_product_code = i2.top_product_code
 AND i1.material_code = i2.material_code
 AND i1.id < i2.id;

DELETE g1 FROM lp_price_prepare_gap g1
JOIN lp_price_prepare_gap g2
  ON g1.oa_no = g2.oa_no
 AND g1.period_month = g2.period_month
 AND g1.top_product_code = g2.top_product_code
 AND g1.material_code = g2.material_code
 AND g1.gap_material_code = g2.gap_material_code
 AND g1.gap_type = g2.gap_type
 AND g1.item_type = g2.item_type
 AND g1.id < g2.id;

CALL v156_add_index_if_not_exists(
  'lp_price_prepare_item',
  'uk_price_prepare_item_current',
  'UNIQUE KEY uk_price_prepare_item_current (oa_no, period_month, top_product_code, material_code)'
);

CALL v156_add_index_if_not_exists(
  'lp_price_prepare_gap',
  'uk_price_prepare_gap_current',
  'UNIQUE KEY uk_price_prepare_gap_current (oa_no, period_month, top_product_code, material_code, gap_material_code, gap_type, item_type)'
);

CALL v156_add_index_if_not_exists(
  'lp_price_prepare_item',
  'idx_price_prepare_item_oa_period_top',
  'KEY idx_price_prepare_item_oa_period_top (oa_no, period_month, top_product_code)'
);

CALL v156_add_index_if_not_exists(
  'lp_price_prepare_gap',
  'idx_price_prepare_gap_oa_period_top',
  'KEY idx_price_prepare_gap_oa_period_top (oa_no, period_month, top_product_code)'
);

DROP PROCEDURE IF EXISTS v156_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v156_drop_index_if_exists;
DROP PROCEDURE IF EXISTS v156_add_index_if_not_exists;
