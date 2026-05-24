-- =============================================================================
-- V114: 产品属性对照表扩展为年度产品属性 + OA 年用量沉淀
-- -----------------------------------------------------------------------------
-- 业务口径：
--   1. 产品属性由技术导入 / 页面维护，不由 OA 报价单覆盖。
--   2. 预计年用量可以由技术导入初始化，后续由 OA 报价单产品行按年度和料号更新。
--   3. 同业务单元 + 年度 + 产品料号只保留一条有效记录。
--   4. 保留旧字段 parent_code / period / product_attr，兼容现有成本试算链路。
-- =============================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS v114_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v114_add_index_if_not_exists;
DROP PROCEDURE IF EXISTS v114_drop_index_if_exists;

DELIMITER $$

CREATE PROCEDURE v114_add_column_if_not_exists(
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

CREATE PROCEDURE v114_add_index_if_not_exists(
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

CREATE PROCEDURE v114_drop_index_if_exists(
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

-- -----------------------------------------------------------------------------
-- 1. 字段扩展
-- -----------------------------------------------------------------------------

CALL v114_add_column_if_not_exists(
  'lp_product_property',
  'property_year',
  '`property_year` INT DEFAULT NULL COMMENT ''年度，如 2026'' AFTER `period`'
);

CALL v114_add_column_if_not_exists(
  'lp_product_property',
  'business_division',
  '`business_division` VARCHAR(120) DEFAULT NULL COMMENT ''事业部，来自技术模板'' AFTER `property_year`'
);

CALL v114_add_column_if_not_exists(
  'lp_product_property',
  'product_code',
  '`product_code` VARCHAR(80) DEFAULT NULL COMMENT ''产品料号，年度匹配主键'' AFTER `business_division`'
);

CALL v114_add_column_if_not_exists(
  'lp_product_property',
  'product_name',
  '`product_name` VARCHAR(120) DEFAULT NULL COMMENT ''产品名称'' AFTER `product_code`'
);

CALL v114_add_column_if_not_exists(
  'lp_product_property',
  'product_model',
  '`product_model` VARCHAR(120) DEFAULT NULL COMMENT ''产品型号'' AFTER `product_name`'
);

CALL v114_add_column_if_not_exists(
  'lp_product_property',
  'product_spec',
  '`product_spec` VARCHAR(120) DEFAULT NULL COMMENT ''产品规格'' AFTER `product_model`'
);

CALL v114_add_column_if_not_exists(
  'lp_product_property',
  'annual_usage',
  '`annual_usage` DECIMAL(18,6) DEFAULT NULL COMMENT ''预计年用量'' AFTER `product_spec`'
);

CALL v114_add_column_if_not_exists(
  'lp_product_property',
  'remark',
  '`remark` VARCHAR(500) DEFAULT NULL COMMENT ''备注'' AFTER `annual_usage`'
);

CALL v114_add_column_if_not_exists(
  'lp_product_property',
  'attr_source_type',
  '`attr_source_type` VARCHAR(32) DEFAULT NULL COMMENT ''产品属性来源：TECH_IMPORT/MANUAL'' AFTER `remark`'
);

CALL v114_add_column_if_not_exists(
  'lp_product_property',
  'attr_source_batch_no',
  '`attr_source_batch_no` VARCHAR(128) DEFAULT NULL COMMENT ''产品属性来源批次号'' AFTER `attr_source_type`'
);

CALL v114_add_column_if_not_exists(
  'lp_product_property',
  'annual_usage_source_type',
  '`annual_usage_source_type` VARCHAR(32) DEFAULT NULL COMMENT ''年用量来源：TECH_IMPORT/OA_QUOTE_USAGE/MANUAL'' AFTER `attr_source_batch_no`'
);

CALL v114_add_column_if_not_exists(
  'lp_product_property',
  'annual_usage_source_batch_no',
  '`annual_usage_source_batch_no` VARCHAR(128) DEFAULT NULL COMMENT ''年用量来源批次号'' AFTER `annual_usage_source_type`'
);

CALL v114_add_column_if_not_exists(
  'lp_product_property',
  'annual_usage_oa_no',
  '`annual_usage_oa_no` VARCHAR(64) DEFAULT NULL COMMENT ''年用量来源 OA 报价单号'' AFTER `annual_usage_source_batch_no`'
);

CALL v114_add_column_if_not_exists(
  'lp_product_property',
  'annual_usage_oa_line_id',
  '`annual_usage_oa_line_id` VARCHAR(128) DEFAULT NULL COMMENT ''年用量来源 OA 产品行 ID'' AFTER `annual_usage_oa_no`'
);

CALL v114_add_column_if_not_exists(
  'lp_product_property',
  'annual_usage_updated_at',
  '`annual_usage_updated_at` DATETIME DEFAULT NULL COMMENT ''年用量更新时间'' AFTER `annual_usage_oa_line_id`'
);

CALL v114_add_column_if_not_exists(
  'lp_product_property',
  'effective_from',
  '`effective_from` DATE DEFAULT NULL COMMENT ''生效开始日期'' AFTER `annual_usage_updated_at`'
);

CALL v114_add_column_if_not_exists(
  'lp_product_property',
  'effective_to',
  '`effective_to` DATE DEFAULT NULL COMMENT ''生效结束日期'' AFTER `effective_from`'
);

CALL v114_add_column_if_not_exists(
  'lp_product_property',
  'match_risk_flag',
  '`match_risk_flag` TINYINT NOT NULL DEFAULT 0 COMMENT ''是否降级匹配：1是，0否'' AFTER `effective_to`'
);

CALL v114_add_column_if_not_exists(
  'lp_product_property',
  'match_risk_reason',
  '`match_risk_reason` VARCHAR(500) DEFAULT NULL COMMENT ''降级匹配原因'' AFTER `match_risk_flag`'
);

-- -----------------------------------------------------------------------------
-- 2. 旧字段回填新字段
-- -----------------------------------------------------------------------------

UPDATE `lp_product_property`
   SET `business_unit_type` = 'COMMERCIAL'
 WHERE `business_unit_type` IS NULL
    OR `business_unit_type` = '';

UPDATE `lp_product_property`
   SET `property_year` = CASE
       WHEN `period` REGEXP '^[0-9]{4}(-[0-9]{2})?$' THEN CAST(LEFT(`period`, 4) AS UNSIGNED)
       ELSE YEAR(CURDATE())
     END
 WHERE `property_year` IS NULL;

UPDATE `lp_product_property`
   SET `business_division` = COALESCE(NULLIF(`business_division`, ''), NULLIF(`level1_name`, '')),
       `product_code` = COALESCE(NULLIF(`product_code`, ''), NULLIF(`parent_code`, '')),
       `product_name` = COALESCE(NULLIF(`product_name`, ''), NULLIF(`parent_name`, '')),
       `product_model` = COALESCE(NULLIF(`product_model`, ''), NULLIF(`parent_model`, '')),
       `product_spec` = COALESCE(NULLIF(`product_spec`, ''), NULLIF(`parent_spec`, '')),
       `attr_source_type` = COALESCE(NULLIF(`attr_source_type`, ''), 'TECH_IMPORT')
 WHERE `id` IS NOT NULL;

UPDATE `lp_product_property`
   SET `annual_usage_source_type` = COALESCE(NULLIF(`annual_usage_source_type`, ''), 'TECH_IMPORT'),
       `annual_usage_updated_at` = COALESCE(`annual_usage_updated_at`, `updated_at`, `created_at`, NOW())
 WHERE `annual_usage` IS NOT NULL;

UPDATE `lp_product_property`
   SET `match_risk_flag` = 1,
       `match_risk_reason` = COALESCE(NULLIF(`match_risk_reason`, ''), '历史数据缺少产品料号，不能参与 OA 年用量自动更新')
 WHERE `product_code` IS NULL
    OR `product_code` = '';

-- -----------------------------------------------------------------------------
-- 3. 清理历史同料号多月份记录
-- -----------------------------------------------------------------------------
-- 旧表允许同一料号按月份维护多条。年度模型只保留同业务单元 + 年度 + 料号最新一条：
-- period 最新优先，其次 updated_at 最新，最后 id 最大。

DELETE older
  FROM `lp_product_property` older
  JOIN (
    SELECT id
      FROM (
        SELECT id,
               ROW_NUMBER() OVER (
                 PARTITION BY `business_unit_type`, `property_year`, `product_code`
                 ORDER BY `period` DESC, `updated_at` DESC, `id` DESC
               ) AS rn
          FROM `lp_product_property`
         WHERE `property_year` IS NOT NULL
           AND `product_code` IS NOT NULL
           AND `product_code` <> ''
      ) ranked
     WHERE ranked.rn > 1
  ) duplicate_rows
    ON duplicate_rows.id = older.id;

-- -----------------------------------------------------------------------------
-- 4. 旧字段同步。period 统一压到年度首月，保留旧查询可用。
-- -----------------------------------------------------------------------------
-- 旧唯一键不含 business_unit_type，且与年度 period 同步存在冲突；先降级为普通索引。

CALL v114_drop_index_if_exists('lp_product_property', 'uk_product_property_unique');

CALL v114_add_index_if_not_exists(
  'lp_product_property',
  'idx_product_property_legacy_key',
  'INDEX `idx_product_property_legacy_key` (`level1_code`, `parent_code`, `period`)'
);

UPDATE `lp_product_property`
   SET `level1_name` = COALESCE(NULLIF(`business_division`, ''), `level1_name`),
       `parent_code` = COALESCE(NULLIF(`product_code`, ''), `parent_code`),
       `parent_name` = COALESCE(NULLIF(`product_name`, ''), `parent_name`),
       `parent_model` = COALESCE(NULLIF(`product_model`, ''), `parent_model`),
       `parent_spec` = COALESCE(NULLIF(`product_spec`, ''), `parent_spec`),
       `period` = CASE
         WHEN `property_year` IS NOT NULL THEN CONCAT(CAST(`property_year` AS CHAR), '-01')
         ELSE `period`
       END
 WHERE `id` IS NOT NULL;

-- -----------------------------------------------------------------------------
-- 5. 年度索引和唯一键
-- -----------------------------------------------------------------------------

CALL v114_add_index_if_not_exists(
  'lp_product_property',
  'uk_product_property_year_code',
  'UNIQUE KEY `uk_product_property_year_code` (`business_unit_type`, `property_year`, `product_code`)'
);

CALL v114_add_index_if_not_exists(
  'lp_product_property',
  'idx_product_property_year_division',
  'INDEX `idx_product_property_year_division` (`business_unit_type`, `property_year`, `business_division`)'
);

CALL v114_add_index_if_not_exists(
  'lp_product_property',
  'idx_product_property_year_model',
  'INDEX `idx_product_property_year_model` (`business_unit_type`, `property_year`, `business_division`, `product_name`, `product_model`, `product_spec`)'
);

CALL v114_add_index_if_not_exists(
  'lp_product_property',
  'idx_product_property_attr_source',
  'INDEX `idx_product_property_attr_source` (`attr_source_type`, `attr_source_batch_no`)'
);

CALL v114_add_index_if_not_exists(
  'lp_product_property',
  'idx_product_property_usage_oa',
  'INDEX `idx_product_property_usage_oa` (`annual_usage_oa_no`, `annual_usage_oa_line_id`)'
);

DROP PROCEDURE IF EXISTS v114_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v114_add_index_if_not_exists;
DROP PROCEDURE IF EXISTS v114_drop_index_if_exists;
