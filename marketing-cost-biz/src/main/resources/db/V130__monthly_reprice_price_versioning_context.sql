-- =============================================================================
-- V130: 月度调价价格版本化上下文
-- -----------------------------------------------------------------------------
-- 1. 月度调价批次固化 price_as_of_time，保证同一 reprice_no 下所有任务取价口径一致。
-- 2. 月度调价批次记录 bom_source_policy，当前固定 HISTORICAL_OA_BOM。
-- 3. 为价格路由、固定价、区间价补齐/强化 effective_from/effective_to 查询索引。
--
-- 兼容说明：
--   历史 lp_monthly_reprice_batch 记录的 price_as_of_time 回填 created_at；无法定位发起
--   瞬时系统时钟时，created_at 是最保守且可追溯的历史切点。
--   价格基础表历史 effective_from 若为空，本迁移不强制改写，仍表示不限早；
--   effective_to 为空仍表示当前有效版本。
-- =============================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS add_column_if_not_exists_v130;
DELIMITER //
CREATE PROCEDURE add_column_if_not_exists_v130(
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
DELIMITER ;

DROP PROCEDURE IF EXISTS add_index_if_not_exists_v130;
DELIMITER //
CREATE PROCEDURE add_index_if_not_exists_v130(
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

CALL add_column_if_not_exists_v130(
  'lp_monthly_reprice_batch',
  'price_as_of_time',
  'DATETIME NULL COMMENT ''本批次统一取价时点'' AFTER `pricing_month`'
);

CALL add_column_if_not_exists_v130(
  'lp_monthly_reprice_batch',
  'bom_source_policy',
  'VARCHAR(32) NOT NULL DEFAULT ''HISTORICAL_OA_BOM'' COMMENT ''BOM来源策略'' AFTER `price_as_of_time`'
);

UPDATE lp_monthly_reprice_batch
   SET price_as_of_time = COALESCE(price_as_of_time, created_at, updated_at, NOW()),
       bom_source_policy = COALESCE(NULLIF(bom_source_policy, ''), 'HISTORICAL_OA_BOM')
 WHERE price_as_of_time IS NULL
    OR bom_source_policy IS NULL
    OR bom_source_policy = '';

ALTER TABLE lp_monthly_reprice_batch
  MODIFY COLUMN price_as_of_time DATETIME NOT NULL COMMENT '本批次统一取价时点',
  MODIFY COLUMN bom_source_policy VARCHAR(32) NOT NULL DEFAULT 'HISTORICAL_OA_BOM' COMMENT 'BOM来源策略';

CALL add_index_if_not_exists_v130(
  'lp_monthly_reprice_batch',
  'idx_monthly_reprice_price_as_of',
  'INDEX `idx_monthly_reprice_price_as_of` (`pricing_month`, `business_unit_type`, `price_as_of_time`)'
);

CALL add_column_if_not_exists_v130(
  'lp_material_price_type',
  'effective_from',
  'DATE NULL DEFAULT NULL COMMENT ''生效起始日期，NULL表示不限早'''
);
CALL add_column_if_not_exists_v130(
  'lp_material_price_type',
  'effective_to',
  'DATE NULL DEFAULT NULL COMMENT ''失效日期，NULL表示当前有效'''
);
CALL add_index_if_not_exists_v130(
  'lp_material_price_type',
  'idx_price_type_version_lookup',
  'INDEX `idx_price_type_version_lookup` (`material_code`, `period`, `priority`, `effective_from`, `effective_to`)'
);

CALL add_column_if_not_exists_v130(
  'lp_price_fixed_item',
  'effective_from',
  'DATE NULL DEFAULT NULL COMMENT ''生效起始日期，NULL表示不限早'''
);
CALL add_column_if_not_exists_v130(
  'lp_price_fixed_item',
  'effective_to',
  'DATE NULL DEFAULT NULL COMMENT ''失效日期，NULL表示当前有效'''
);
CALL add_index_if_not_exists_v130(
  'lp_price_fixed_item',
  'idx_price_fixed_version_lookup',
  'INDEX `idx_price_fixed_version_lookup` (`material_code`, `source_type`, `business_unit_type`, `effective_from`, `effective_to`)'
);

CALL add_column_if_not_exists_v130(
  'lp_price_range_item',
  'effective_from',
  'DATE NULL DEFAULT NULL COMMENT ''生效起始日期，NULL表示不限早'''
);
CALL add_column_if_not_exists_v130(
  'lp_price_range_item',
  'effective_to',
  'DATE NULL DEFAULT NULL COMMENT ''失效日期，NULL表示当前有效'''
);
CALL add_index_if_not_exists_v130(
  'lp_price_range_item',
  'idx_price_range_version_lookup',
  'INDEX `idx_price_range_version_lookup` (`material_code`, `business_unit_type`, `effective_from`, `effective_to`)'
);

DROP PROCEDURE IF EXISTS add_index_if_not_exists_v130;
DROP PROCEDURE IF EXISTS add_column_if_not_exists_v130;
