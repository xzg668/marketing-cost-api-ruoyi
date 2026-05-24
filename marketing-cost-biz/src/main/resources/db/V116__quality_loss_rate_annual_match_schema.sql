-- =============================================================================
-- V116: 报价净损失率配置表改为年度 + 料号/型号匹配口径
-- -----------------------------------------------------------------------------
-- 业务口径：
--   1. 净损失率配置仍使用 lp_quality_loss_rate，不另建表。
--   2. 成本核算后续按“产品料号优先，未命中再按 U9 主档 material_model 匹配产品型号”取数。
--   3. 配置表唯一键使用 business_unit_type + rate_year + match_level + match_key。
-- =============================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS v116_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v116_add_index_if_not_exists;
DROP PROCEDURE IF EXISTS v116_drop_index_if_exists;

DELIMITER $$

CREATE PROCEDURE v116_add_column_if_not_exists(
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

CREATE PROCEDURE v116_add_index_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_index_name VARCHAR(64),
  IN p_index_def TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1
      FROM INFORMATION_SCHEMA.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
  ) AND NOT EXISTS (
    SELECT 1
      FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND INDEX_NAME = p_index_name
  ) THEN
    SET @sql = CONCAT('ALTER TABLE `', p_table_name, '` ADD ', p_index_def);
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

CREATE PROCEDURE v116_drop_index_if_exists(
  IN p_table_name VARCHAR(64),
  IN p_index_name VARCHAR(64)
)
BEGIN
  IF EXISTS (
    SELECT 1
      FROM INFORMATION_SCHEMA.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
  ) AND EXISTS (
    SELECT 1
      FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND INDEX_NAME = p_index_name
  ) THEN
    SET @sql = CONCAT('ALTER TABLE `', p_table_name, '` DROP INDEX `', p_index_name, '`');
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

DELIMITER ;

CALL v116_add_column_if_not_exists(
  'lp_quality_loss_rate',
  'rate_year',
  '`rate_year` INT DEFAULT NULL COMMENT ''年度，如 2026'' AFTER `period`'
);

CALL v116_add_column_if_not_exists(
  'lp_quality_loss_rate',
  'business_division',
  '`business_division` VARCHAR(120) DEFAULT NULL COMMENT ''事业部，展示和管理用，不参与兜底匹配'' AFTER `business_unit_type`'
);

CALL v116_add_column_if_not_exists(
  'lp_quality_loss_rate',
  'product_code',
  '`product_code` VARCHAR(80) DEFAULT NULL COMMENT ''产品料号，净损失率第一匹配键'' AFTER `business_division`'
);

CALL v116_add_column_if_not_exists(
  'lp_quality_loss_rate',
  'product_name',
  '`product_name` VARCHAR(120) DEFAULT NULL COMMENT ''产品名称'' AFTER `product_code`'
);

CALL v116_add_column_if_not_exists(
  'lp_quality_loss_rate',
  'product_model',
  '`product_model` VARCHAR(120) DEFAULT NULL COMMENT ''产品型号，净损失率第二匹配键'' AFTER `product_name`'
);

CALL v116_add_column_if_not_exists(
  'lp_quality_loss_rate',
  'product_spec',
  '`product_spec` VARCHAR(120) DEFAULT NULL COMMENT ''产品规格'' AFTER `product_model`'
);

CALL v116_add_column_if_not_exists(
  'lp_quality_loss_rate',
  'remark',
  '`remark` VARCHAR(500) DEFAULT NULL COMMENT ''备注'' AFTER `source_basis`'
);

CALL v116_add_column_if_not_exists(
  'lp_quality_loss_rate',
  'source_type',
  '`source_type` VARCHAR(32) DEFAULT NULL COMMENT ''来源：MANUAL/EXCEL_IMPORT'' AFTER `remark`'
);

CALL v116_add_column_if_not_exists(
  'lp_quality_loss_rate',
  'source_batch_no',
  '`source_batch_no` VARCHAR(128) DEFAULT NULL COMMENT ''导入批次号'' AFTER `source_type`'
);

CALL v116_add_column_if_not_exists(
  'lp_quality_loss_rate',
  'match_level',
  '`match_level` VARCHAR(32) DEFAULT NULL COMMENT ''匹配层级：MATERIAL_CODE/MATERIAL_MODEL'' AFTER `source_batch_no`'
);

CALL v116_add_column_if_not_exists(
  'lp_quality_loss_rate',
  'match_key',
  '`match_key` VARCHAR(120) DEFAULT NULL COMMENT ''匹配键：产品料号或产品型号'' AFTER `match_level`'
);

UPDATE lp_quality_loss_rate
   SET business_unit_type = COALESCE(business_unit_type, 'COMMERCIAL'),
       rate_year = COALESCE(rate_year, CAST(LEFT(period, 4) AS UNSIGNED)),
       business_division = COALESCE(business_division, business_unit),
       source_type = COALESCE(source_type, 'MANUAL')
 WHERE rate_year IS NULL
    OR business_division IS NULL
    OR source_type IS NULL
    OR business_unit_type IS NULL;

UPDATE lp_quality_loss_rate
   SET match_level = 'LEGACY',
       match_key = CONCAT('LEGACY-', id),
       product_model = COALESCE(product_model, product_subcategory)
 WHERE match_level IS NULL
   AND match_key IS NULL;

CALL v116_drop_index_if_exists('lp_quality_loss_rate', 'uk_quality_loss_match');
CALL v116_add_index_if_not_exists(
  'lp_quality_loss_rate',
  'uk_quality_loss_match',
  'UNIQUE KEY `uk_quality_loss_match` (`business_unit_type`, `rate_year`, `match_level`, `match_key`)'
);

CALL v116_add_index_if_not_exists(
  'lp_quality_loss_rate',
  'idx_quality_loss_material_code',
  'KEY `idx_quality_loss_material_code` (`business_unit_type`, `rate_year`, `product_code`)'
);

CALL v116_add_index_if_not_exists(
  'lp_quality_loss_rate',
  'idx_quality_loss_material_model',
  'KEY `idx_quality_loss_material_model` (`business_unit_type`, `rate_year`, `product_model`)'
);

DROP PROCEDURE IF EXISTS v116_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v116_add_index_if_not_exists;
DROP PROCEDURE IF EXISTS v116_drop_index_if_exists;
