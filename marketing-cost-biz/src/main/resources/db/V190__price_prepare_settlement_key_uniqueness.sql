-- =============================================================================
-- V190: 价格准备明细按稳定结算键防重
-- -----------------------------------------------------------------------------
-- V185 的批次唯一键只包含料号，会错误阻止同一料号出现在不同 BOM 路径。
-- 历史行 settlement_key 允许为空；新生成行由 (prepare_no, settlement_key) 精确防重。
-- =============================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS v190_drop_index_if_exists;
DROP PROCEDURE IF EXISTS v190_add_index_if_not_exists;

DELIMITER $$

CREATE PROCEDURE v190_drop_index_if_exists(
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
    SET @ddl = CONCAT(
      'ALTER TABLE `', p_table_name, '` DROP INDEX `', p_index_name, '`');
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END$$

CREATE PROCEDURE v190_add_index_if_not_exists(
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
    SET @ddl = CONCAT(
      'ALTER TABLE `', p_table_name, '` ADD ', p_index_definition);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END$$

DELIMITER ;

-- 旧键会把同批次、同产品行、同料号但不同 BOM 路径的结算行错误合并。
CALL v190_drop_index_if_exists(
  'lp_price_prepare_item',
  'uk_price_prepare_item_batch'
);

-- V187 的同列普通索引由唯一索引覆盖，避免维护两份重复索引。
CALL v190_drop_index_if_exists(
  'lp_price_prepare_item',
  'idx_price_prepare_item_settlement'
);

CALL v190_add_index_if_not_exists(
  'lp_price_prepare_item',
  'uk_price_prepare_item_settlement',
  'UNIQUE KEY uk_price_prepare_item_settlement (prepare_no, settlement_key)'
);

DROP PROCEDURE IF EXISTS v190_drop_index_if_exists;
DROP PROCEDURE IF EXISTS v190_add_index_if_not_exists;
