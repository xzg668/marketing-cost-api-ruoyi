-- =============================================================================
-- V177: 行情因素区间价结构
-- -----------------------------------------------------------------------------
-- 1. 新增 lp_price_range_factor_rule，记录物料当前区间价受哪个行情因素影响。
-- 2. 扩展 lp_price_range_item，兼容旧数量区间价(QTY)，新增行情因素区间价(FACTOR)字段。
-- 3. 旧数据默认 range_basis=QTY、current_flag=1，不要求 factor_rule_id。
-- =============================================================================

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `lp_price_range_factor_rule` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `business_unit_type` VARCHAR(20) DEFAULT NULL COMMENT '业务单元',
  `material_code` VARCHAR(64) NOT NULL COMMENT '物料代码',
  `material_name` VARCHAR(128) DEFAULT NULL COMMENT '物料名称',
  `spec_model` VARCHAR(128) DEFAULT NULL COMMENT '规格型号/图号',
  `factor_code` VARCHAR(32) NOT NULL COMMENT '影响因素编码: CU/ZN/AL/GOLD等',
  `factor_name` VARCHAR(64) DEFAULT NULL COMMENT '影响因素名称',
  `factor_unit` VARCHAR(32) DEFAULT NULL COMMENT '因素单位，如 元/吨',
  `price_unit` VARCHAR(32) DEFAULT NULL COMMENT '价格单位，如 元/米、元/只',
  `version_no` INT NOT NULL DEFAULT 1 COMMENT '同一物料区间规则版本号',
  `import_batch_no` VARCHAR(64) NOT NULL COMMENT '导入批次号',
  `source_file` VARCHAR(255) DEFAULT NULL COMMENT '来源文件',
  `source_sheet` VARCHAR(128) DEFAULT NULL COMMENT '来源sheet',
  `effective_from` DATE NOT NULL COMMENT '生效开始日期，含',
  `effective_to` DATE DEFAULT NULL COMMENT '生效结束日期，不含；NULL表示当前有效',
  `current_flag` TINYINT NOT NULL DEFAULT 1 COMMENT '是否当前版本 1=是 0=否',
  `created_at` DATETIME NOT NULL COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_factor_rule_material` (`business_unit_type`, `material_code`),
  KEY `idx_factor_rule_factor` (`factor_code`),
  KEY `idx_factor_rule_current` (`business_unit_type`, `material_code`, `current_flag`),
  KEY `idx_factor_rule_effective` (`effective_from`, `effective_to`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='行情因素区间价影响因素规则表';

DROP PROCEDURE IF EXISTS v177_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v177_add_index_if_not_exists;

DELIMITER //
CREATE PROCEDURE v177_add_column_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_column_name VARCHAR(64),
  IN p_column_def TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1
      FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
  ) AND NOT EXISTS (
    SELECT 1
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND COLUMN_NAME = p_column_name
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table_name, '` ADD COLUMN `', p_column_name, '` ', p_column_def);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END//

CREATE PROCEDURE v177_add_index_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_index_name VARCHAR(64),
  IN p_index_def TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1
      FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
  ) AND NOT EXISTS (
    SELECT 1
      FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND INDEX_NAME = p_index_name
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table_name, '` ADD ', p_index_def);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END//
DELIMITER ;

CALL v177_add_column_if_not_exists(
  'lp_price_range_item',
  'range_basis',
  'VARCHAR(16) NOT NULL DEFAULT ''QTY'' COMMENT ''区间依据: QTY=数量区间 FACTOR=行情因素区间'' AFTER `range_high`'
);

CALL v177_add_column_if_not_exists(
  'lp_price_range_item',
  'factor_rule_id',
  'BIGINT DEFAULT NULL COMMENT ''行情因素区间规则ID'' AFTER `range_basis`'
);

CALL v177_add_column_if_not_exists(
  'lp_price_range_item',
  'factor_code',
  'VARCHAR(32) DEFAULT NULL COMMENT ''影响因素编码'' AFTER `factor_rule_id`'
);

CALL v177_add_column_if_not_exists(
  'lp_price_range_item',
  'import_batch_no',
  'VARCHAR(64) DEFAULT NULL COMMENT ''导入批次号'' AFTER `factor_code`'
);

CALL v177_add_column_if_not_exists(
  'lp_price_range_item',
  'current_flag',
  'TINYINT NOT NULL DEFAULT 1 COMMENT ''是否当前版本 1=是 0=否'' AFTER `import_batch_no`'
);

UPDATE `lp_price_range_item`
   SET `range_basis` = 'QTY'
 WHERE `range_basis` IS NULL OR `range_basis` = '';

UPDATE `lp_price_range_item`
   SET `current_flag` = 1
 WHERE `current_flag` IS NULL;

CALL v177_add_index_if_not_exists(
  'lp_price_range_item',
  'idx_range_factor_current',
  'KEY `idx_range_factor_current` (`business_unit_type`, `material_code`, `factor_code`, `current_flag`)'
);

CALL v177_add_index_if_not_exists(
  'lp_price_range_item',
  'idx_range_factor_rule',
  'KEY `idx_range_factor_rule` (`factor_rule_id`)'
);

CALL v177_add_index_if_not_exists(
  'lp_price_range_item',
  'idx_range_basis',
  'KEY `idx_range_basis` (`range_basis`)'
);

DROP PROCEDURE IF EXISTS v177_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v177_add_index_if_not_exists;
