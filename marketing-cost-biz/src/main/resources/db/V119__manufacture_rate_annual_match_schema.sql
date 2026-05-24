-- =============================================================================
-- V119: 制造费用率配置表改为年度 + 多层级匹配口径
-- -----------------------------------------------------------------------------
-- 业务口径：
--   1. 制造费用率配置仍使用 lp_manufacture_rate，不另建表。
--   2. 成本核算按“成品料号、成本料号型号、成本料号名称+事业部、成品料号事业部”逐级取数。
--   3. 配置表唯一键使用 business_unit_type + rate_year + match_level + match_key。
-- =============================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS v119_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v119_add_index_if_not_exists;
DROP PROCEDURE IF EXISTS v119_drop_index_if_exists;

DELIMITER $$

CREATE PROCEDURE v119_add_column_if_not_exists(
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

CREATE PROCEDURE v119_add_index_if_not_exists(
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

CREATE PROCEDURE v119_drop_index_if_exists(
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

CALL v119_add_column_if_not_exists(
  'lp_manufacture_rate',
  'rate_year',
  '`rate_year` INT DEFAULT NULL COMMENT ''年度，如 2026'' AFTER `period`'
);

CALL v119_add_column_if_not_exists(
  'lp_manufacture_rate',
  'business_division',
  '`business_division` VARCHAR(120) DEFAULT NULL COMMENT ''事业部，制造费用率展示和匹配用'' AFTER `business_unit_type`'
);

CALL v119_add_column_if_not_exists(
  'lp_manufacture_rate',
  'product_code',
  '`product_code` VARCHAR(80) DEFAULT NULL COMMENT ''料号，制造费用率第一匹配键'' AFTER `business_division`'
);

CALL v119_add_column_if_not_exists(
  'lp_manufacture_rate',
  'product_name',
  '`product_name` VARCHAR(120) DEFAULT NULL COMMENT ''产品名称，事业部+产品名称匹配用'' AFTER `product_code`'
);

CALL v119_add_column_if_not_exists(
  'lp_manufacture_rate',
  'remark',
  '`remark` VARCHAR(500) DEFAULT NULL COMMENT ''备注'' AFTER `product_model`'
);

CALL v119_add_column_if_not_exists(
  'lp_manufacture_rate',
  'source_type',
  '`source_type` VARCHAR(32) DEFAULT NULL COMMENT ''来源：MANUAL/EXCEL_IMPORT'' AFTER `remark`'
);

CALL v119_add_column_if_not_exists(
  'lp_manufacture_rate',
  'source_batch_no',
  '`source_batch_no` VARCHAR(128) DEFAULT NULL COMMENT ''导入批次号'' AFTER `source_type`'
);

CALL v119_add_column_if_not_exists(
  'lp_manufacture_rate',
  'match_level',
  '`match_level` VARCHAR(32) DEFAULT NULL COMMENT ''匹配层级：MATERIAL_CODE/MATERIAL_MODEL/DIVISION_PRODUCT_NAME/DIVISION'' AFTER `source_batch_no`'
);

CALL v119_add_column_if_not_exists(
  'lp_manufacture_rate',
  'match_key',
  '`match_key` VARCHAR(240) DEFAULT NULL COMMENT ''匹配键'' AFTER `match_level`'
);

ALTER TABLE lp_manufacture_rate
  MODIFY company VARCHAR(80) NOT NULL DEFAULT '' COMMENT '历史公司字段，新口径不参与匹配',
  MODIFY business_unit VARCHAR(80) NOT NULL DEFAULT '' COMMENT '历史事业部字段，新口径以 business_division 展示',
  MODIFY product_category VARCHAR(80) NOT NULL DEFAULT '' COMMENT '历史产品大类字段，新口径不参与匹配',
  MODIFY product_subcategory VARCHAR(80) NOT NULL DEFAULT '' COMMENT '历史产品小类字段，新口径不参与匹配',
  MODIFY period VARCHAR(20) NOT NULL DEFAULT '' COMMENT '历史期间字段，新口径以 rate_year 匹配';

UPDATE lp_manufacture_rate
   SET business_unit_type = COALESCE(business_unit_type, 'COMMERCIAL'),
       rate_year = COALESCE(rate_year, CAST(LEFT(NULLIF(period, ''), 4) AS UNSIGNED), YEAR(CURDATE())),
       business_division = COALESCE(NULLIF(business_division, ''), NULLIF(business_unit, '')),
       source_type = COALESCE(source_type, 'MANUAL')
 WHERE rate_year IS NULL
    OR business_division IS NULL
    OR source_type IS NULL
    OR business_unit_type IS NULL;

UPDATE lp_manufacture_rate
   SET match_level = 'LEGACY',
       match_key = CONCAT('LEGACY-', id)
 WHERE match_level IS NULL
   AND match_key IS NULL;

CALL v119_drop_index_if_exists('lp_manufacture_rate', 'uk_mfr_unique');
CALL v119_drop_index_if_exists('lp_manufacture_rate', 'uk_manufacture_rate_match');
CALL v119_add_index_if_not_exists(
  'lp_manufacture_rate',
  'uk_manufacture_rate_match',
  'UNIQUE KEY `uk_manufacture_rate_match` (`business_unit_type`, `rate_year`, `match_level`, `match_key`)'
);

CALL v119_add_index_if_not_exists(
  'lp_manufacture_rate',
  'idx_manufacture_rate_product_code',
  'KEY `idx_manufacture_rate_product_code` (`business_unit_type`, `rate_year`, `product_code`)'
);

CALL v119_add_index_if_not_exists(
  'lp_manufacture_rate',
  'idx_manufacture_rate_product_model',
  'KEY `idx_manufacture_rate_product_model` (`business_unit_type`, `rate_year`, `product_model`)'
);

CALL v119_add_index_if_not_exists(
  'lp_manufacture_rate',
  'idx_manufacture_rate_match',
  'KEY `idx_manufacture_rate_match` (`business_unit_type`, `rate_year`, `match_level`, `match_key`)'
);

DROP PROCEDURE IF EXISTS v119_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v119_add_index_if_not_exists;
DROP PROCEDURE IF EXISTS v119_drop_index_if_exists;
