-- =============================================================================
-- V140: 报价单产品 BOM 当月沿用快照
-- -----------------------------------------------------------------------------
-- 业务口径：
--   1. 按 产品料号 + 客户 + 包装方式 + 成本核算年月 保存当月可沿用 BOM 快照。
--   2. 产品行 BOM 状态表补齐成本年月和同步/沿用追溯字段。
--   3. 同一组合当月 active 成功记录由服务层事务切换，数据库只提供查询索引。
-- =============================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS v140_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v140_add_index_if_not_exists;

DELIMITER $$

CREATE PROCEDURE v140_add_column_if_not_exists(
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

CREATE PROCEDURE v140_add_index_if_not_exists(
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

DELIMITER ;

CREATE TABLE IF NOT EXISTS lp_quote_bom_monthly_snapshot (
  id BIGINT NOT NULL AUTO_INCREMENT,
  product_code VARCHAR(64) NOT NULL COMMENT '产品料号',
  customer_code VARCHAR(128) NOT NULL DEFAULT '' COMMENT '客户编码/客户名归一化值，空值统一为空串',
  package_method VARCHAR(128) NOT NULL DEFAULT '' COMMENT '包装方式归一化值，空值统一为空串',
  cost_period_month VARCHAR(7) NOT NULL COMMENT '成本核算年月，格式 YYYY-MM',
  bom_source VARCHAR(32) DEFAULT NULL COMMENT 'BOM 来源：U9/MANUAL/TECH/CMS/UNKNOWN',
  bom_purpose VARCHAR(64) DEFAULT NULL COMMENT 'BOM 生产目的',
  bom_version VARCHAR(128) DEFAULT NULL COMMENT 'BOM 版本',
  sync_type VARCHAR(32) NOT NULL COMMENT '同步类型：AUTO_SYNC/MANUAL_SYNC/REUSE/MANUAL_ENTERED',
  sync_status VARCHAR(32) NOT NULL COMMENT '同步状态：SUCCESS/FAILED/SYNCING',
  sync_at DATETIME DEFAULT NULL COMMENT '同步完成时间',
  sync_by VARCHAR(64) DEFAULT NULL COMMENT '同步操作人',
  source_oa_no VARCHAR(64) DEFAULT NULL COMMENT '来源报价单号',
  source_oa_form_item_id BIGINT DEFAULT NULL COMMENT '来源报价单产品行 ID',
  bom_batch_id VARCHAR(128) DEFAULT NULL COMMENT 'BOM 构建/同步批次号',
  active_flag TINYINT NOT NULL DEFAULT 0 COMMENT '是否当前 active 成功记录：1 是，0 否',
  error_message VARCHAR(1000) DEFAULT NULL COMMENT '失败原因',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_quote_bom_monthly_key (
    product_code,
    customer_code,
    package_method,
    cost_period_month,
    active_flag
  ),
  KEY idx_quote_bom_monthly_source (source_oa_no, source_oa_form_item_id),
  KEY idx_quote_bom_monthly_status (sync_status, active_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报价单产品 BOM 月度沿用快照表';

-- 历史状态表中的空客户/空包装方式先归一化，后续服务层继续保证写入归一。
UPDATE lp_quote_bom_status
   SET customer_code = ''
 WHERE customer_code IS NULL
    OR TRIM(customer_code) = '/'
    OR TRIM(customer_code) = '';

UPDATE lp_quote_bom_status
   SET package_method = ''
 WHERE package_method IS NULL
    OR TRIM(package_method) = '/'
    OR TRIM(package_method) = '';

CALL v140_add_column_if_not_exists(
  'lp_quote_bom_status',
  'cost_period_month',
  '`cost_period_month` VARCHAR(7) DEFAULT NULL COMMENT ''成本核算年月，格式 YYYY-MM'' AFTER `package_method`'
);

CALL v140_add_column_if_not_exists(
  'lp_quote_bom_status',
  'sync_record_id',
  '`sync_record_id` BIGINT DEFAULT NULL COMMENT ''本次同步生成的 lp_quote_bom_monthly_snapshot.id'' AFTER `sync_batch_id`'
);

CALL v140_add_column_if_not_exists(
  'lp_quote_bom_status',
  'reused_from_record_id',
  '`reused_from_record_id` BIGINT DEFAULT NULL COMMENT ''沿用来源 lp_quote_bom_monthly_snapshot.id'' AFTER `sync_record_id`'
);

CALL v140_add_column_if_not_exists(
  'lp_quote_bom_status',
  'sync_at',
  '`sync_at` DATETIME DEFAULT NULL COMMENT ''BOM 同步/沿用完成时间'' AFTER `reused_from_record_id`'
);

CALL v140_add_index_if_not_exists(
  'lp_quote_bom_status',
  'idx_quote_bom_status_cost_key',
  'KEY `idx_quote_bom_status_cost_key` (`product_code`, `customer_code`, `package_method`, `cost_period_month`)'
);

CALL v140_add_index_if_not_exists(
  'lp_quote_bom_status',
  'idx_quote_bom_status_sync_record',
  'KEY `idx_quote_bom_status_sync_record` (`sync_record_id`)'
);

CALL v140_add_index_if_not_exists(
  'lp_quote_bom_status',
  'idx_quote_bom_status_reused_record',
  'KEY `idx_quote_bom_status_reused_record` (`reused_from_record_id`)'
);

DROP PROCEDURE IF EXISTS v140_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v140_add_index_if_not_exists;
