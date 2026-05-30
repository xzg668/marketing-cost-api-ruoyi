-- =============================================================================
-- V148: 删除 BOM 旧下钻规则入口
-- -----------------------------------------------------------------------------
-- BSR-09：结算行生成已统一切换到 lp_bom_settlement_rule / lp_bom_byproduct_cost_rule。
-- 旧规则表和旧追溯列不再参与应用读写，避免后续继续产生双规则口径。
-- =============================================================================

SET NAMES utf8mb4;

DELIMITER $$

DROP PROCEDURE IF EXISTS v148_drop_column_if_exists $$
CREATE PROCEDURE v148_drop_column_if_exists(
  IN p_table_name VARCHAR(64),
  IN p_column_name VARCHAR(64)
)
BEGIN
  IF EXISTS (
    SELECT 1
      FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND COLUMN_NAME = p_column_name
  ) THEN
    SET @sql = CONCAT('ALTER TABLE ', p_table_name, ' DROP COLUMN ', p_column_name);
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

DELIMITER ;

CALL v148_drop_column_if_exists('lp_bom_costing_row', 'matched_drill_rule_id');

DROP PROCEDURE IF EXISTS v148_drop_column_if_exists;

DROP TABLE IF EXISTS bom_stop_drill_rule;
