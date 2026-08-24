-- =============================================================================
-- V221: 删除上线前已废弃的整单 BOM/价格类型人工确认模型
--
-- 当前核算链路以有效 BOM、物料价格类型路由、价格准备批次和产品核算工作区为准。
-- 旧确认表尚未承载上线数据，因此同时删除只用于关联旧确认批次的冗余字段。
-- =============================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS v221_drop_index_if_exists;
DROP PROCEDURE IF EXISTS v221_drop_column_if_exists;

DELIMITER $$

CREATE PROCEDURE v221_drop_index_if_exists(
  IN p_table_name VARCHAR(64),
  IN p_index_name VARCHAR(64)
)
BEGIN
  IF EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.TABLES
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table_name
  ) AND EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table_name
       AND INDEX_NAME = p_index_name
  ) THEN
    SET @sql = CONCAT('ALTER TABLE `', p_table_name, '` DROP INDEX `', p_index_name, '`');
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

CREATE PROCEDURE v221_drop_column_if_exists(
  IN p_table_name VARCHAR(64),
  IN p_column_name VARCHAR(64)
)
BEGIN
  IF EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table_name
       AND COLUMN_NAME = p_column_name
  ) THEN
    SET @sql = CONCAT('ALTER TABLE `', p_table_name, '` DROP COLUMN `', p_column_name, '`');
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

DELIMITER ;

CALL v221_drop_index_if_exists('lp_price_prepare_gap', 'idx_pp_gap_confirm');
CALL v221_drop_index_if_exists('lp_price_prepare_item', 'idx_pp_item_confirm');
CALL v221_drop_index_if_exists('lp_quote_cost_run_version', 'idx_quote_cost_bom_confirm');
CALL v221_drop_index_if_exists('lp_quote_cost_run_version', 'idx_quote_cost_type_confirm');

CALL v221_drop_column_if_exists('lp_cost_run_result', 'price_type_confirm_no');
CALL v221_drop_column_if_exists('lp_price_prepare_batch', 'price_type_confirm_no');
CALL v221_drop_column_if_exists('lp_price_prepare_gap', 'price_type_confirm_no');
CALL v221_drop_column_if_exists('lp_price_prepare_gap', 'price_type_confirm_item_id');
CALL v221_drop_column_if_exists('lp_price_prepare_item', 'price_type_confirm_no');
CALL v221_drop_column_if_exists('lp_price_prepare_item', 'price_type_confirm_item_id');
CALL v221_drop_column_if_exists('lp_quote_cost_run_version', 'bom_confirm_no');
CALL v221_drop_column_if_exists('lp_quote_cost_run_version', 'price_type_confirm_no');

DROP TABLE IF EXISTS `lp_quote_bom_confirmation_log`;
DROP TABLE IF EXISTS `lp_quote_price_type_confirm_item`;
DROP TABLE IF EXISTS `lp_quote_price_type_confirm_batch`;
DROP TABLE IF EXISTS `lp_quote_bom_confirmation`;

DROP PROCEDURE IF EXISTS v221_drop_column_if_exists;
DROP PROCEDURE IF EXISTS v221_drop_index_if_exists;
