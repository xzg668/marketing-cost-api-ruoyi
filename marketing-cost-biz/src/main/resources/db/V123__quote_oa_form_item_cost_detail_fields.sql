-- =============================================================================
-- V123: OA 报价单明细补齐成本明细表核心字段
-- -----------------------------------------------------------------------------
-- FI-SC-020 成本明细表存在“成本有效期(月)、不锈钢/SUS 重量、铜重”等一等业务字段。
-- 这些字段不能只进入 item extra_fields，否则后续明细查询和成本链路无法稳定取值。
-- =============================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS v123_add_column_if_not_exists;

DELIMITER $$

CREATE PROCEDURE v123_add_column_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_column_name VARCHAR(64),
  IN p_column_def TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1
      FROM INFORMATION_SCHEMA.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
  ) AND NOT EXISTS (
    SELECT 1
      FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND COLUMN_NAME = p_column_name
  ) THEN
    SET @sql = CONCAT('ALTER TABLE `', p_table_name, '` ADD COLUMN ', p_column_def);
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

DELIMITER ;

CALL v123_add_column_if_not_exists(
  'oa_form_item',
  'valid_month',
  '`valid_month` INT DEFAULT NULL COMMENT ''成本有效期（月）'' AFTER `management_cost`'
);

CALL v123_add_column_if_not_exists(
  'oa_form_item',
  'sus304_weight_g',
  '`sus304_weight_g` DECIMAL(18,6) DEFAULT NULL COMMENT ''不锈钢SUS304重量（克）'' AFTER `valid_month`'
);

CALL v123_add_column_if_not_exists(
  'oa_form_item',
  'sus316_weight_g',
  '`sus316_weight_g` DECIMAL(18,6) DEFAULT NULL COMMENT ''不锈钢SUS316重量（克）'' AFTER `sus304_weight_g`'
);

CALL v123_add_column_if_not_exists(
  'oa_form_item',
  'copper_weight_g',
  '`copper_weight_g` DECIMAL(18,6) DEFAULT NULL COMMENT ''铜重（克）'' AFTER `sus316_weight_g`'
);

DROP PROCEDURE IF EXISTS v123_add_column_if_not_exists;
