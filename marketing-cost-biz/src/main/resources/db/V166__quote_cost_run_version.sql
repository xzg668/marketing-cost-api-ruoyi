-- =============================================================================
-- V166: 单产品核算工作台成本试算版本
-- -----------------------------------------------------------------------------
-- QWB-01-04：新增成本试算/确认版本表，并让历史成本结果表可按产品行和试算批次追溯。
-- =============================================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS lp_quote_cost_run_version (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  cost_run_no VARCHAR(64) NOT NULL COMMENT '试算批次号',
  version_no VARCHAR(64) DEFAULT NULL COMMENT '确认版本号',
  oa_no VARCHAR(64) NOT NULL COMMENT 'OA单号',
  oa_form_item_id BIGINT NOT NULL COMMENT 'OA产品明细行ID',
  product_code VARCHAR(64) NOT NULL COMMENT '产品料号',
  pricing_month VARCHAR(7) NOT NULL COMMENT '计价月份 YYYY-MM',
  result_period VARCHAR(7) NOT NULL COMMENT '成本结果期间 YYYY-MM',
  bom_confirm_no VARCHAR(64) DEFAULT NULL COMMENT 'BOM确认批次号',
  price_type_confirm_no VARCHAR(64) DEFAULT NULL COMMENT '价格类型确认批次号',
  price_prepare_no VARCHAR(64) DEFAULT NULL COMMENT '价格准备批次号',
  status VARCHAR(32) NOT NULL DEFAULT 'TRIAL' COMMENT '状态：TRIAL/CONFIRMED/VOIDED/STALE',
  total_cost DECIMAL(20,6) DEFAULT NULL COMMENT '不含税总成本',
  part_item_count INT NOT NULL DEFAULT 0 COMMENT '物料明细行数',
  cost_item_count INT NOT NULL DEFAULT 0 COMMENT '成本项行数',
  trial_started_at DATETIME DEFAULT NULL COMMENT '试算开始时间',
  trial_finished_at DATETIME DEFAULT NULL COMMENT '试算完成时间',
  confirmed_by VARCHAR(64) DEFAULT NULL COMMENT '确认人',
  confirmed_at DATETIME DEFAULT NULL COMMENT '确认时间',
  confirm_message VARCHAR(1000) DEFAULT NULL COMMENT '确认说明',
  business_unit_type VARCHAR(32) DEFAULT NULL COMMENT '业务单元类型',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_quote_cost_run_no (cost_run_no),
  UNIQUE KEY uk_quote_cost_version_no (version_no),
  KEY idx_quote_cost_item_status (oa_no, oa_form_item_id, product_code, status),
  KEY idx_quote_cost_prepare (price_prepare_no),
  KEY idx_quote_cost_bom_confirm (bom_confirm_no),
  KEY idx_quote_cost_type_confirm (price_type_confirm_no),
  KEY idx_quote_cost_bu (business_unit_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='报价单产品成本试算和确认版本表';

DELIMITER $$

DROP PROCEDURE IF EXISTS v166_add_column_if_not_exists $$
CREATE PROCEDURE v166_add_column_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_column_name VARCHAR(64),
  IN p_column_def TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
      FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND COLUMN_NAME = p_column_name
  ) THEN
    SET @sql = CONCAT('ALTER TABLE ', p_table_name, ' ADD COLUMN ', p_column_name, ' ', p_column_def);
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

DROP PROCEDURE IF EXISTS v166_add_index_if_not_exists $$
CREATE PROCEDURE v166_add_index_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_index_name VARCHAR(64),
  IN p_index_def TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
      FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND INDEX_NAME = p_index_name
  ) THEN
    SET @sql = CONCAT('ALTER TABLE ', p_table_name, ' ADD ', p_index_def);
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

DROP PROCEDURE IF EXISTS v166_drop_index_if_exists $$
CREATE PROCEDURE v166_drop_index_if_exists(
  IN p_table_name VARCHAR(64),
  IN p_index_name VARCHAR(64)
)
BEGIN
  IF EXISTS (
    SELECT 1
      FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND INDEX_NAME = p_index_name
  ) THEN
    SET @sql = CONCAT('ALTER TABLE ', p_table_name, ' DROP INDEX ', p_index_name);
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

DELIMITER ;

CALL v166_add_column_if_not_exists(
  'lp_cost_run_result',
  'oa_form_item_id',
  'BIGINT DEFAULT NULL COMMENT ''OA产品明细行ID'' AFTER oa_no'
);

CALL v166_add_column_if_not_exists(
  'lp_cost_run_result',
  'cost_run_version_id',
  'BIGINT DEFAULT NULL COMMENT ''成本试算版本ID'' AFTER oa_form_item_id'
);

CALL v166_add_column_if_not_exists(
  'lp_cost_run_result',
  'cost_run_no',
  'VARCHAR(64) DEFAULT NULL COMMENT ''试算批次号'' AFTER cost_run_version_id'
);

CALL v166_add_column_if_not_exists(
  'lp_cost_run_result',
  'pricing_month',
  'VARCHAR(7) DEFAULT NULL COMMENT ''计价月份 YYYY-MM'' AFTER period'
);

CALL v166_add_column_if_not_exists(
  'lp_cost_run_result',
  'price_prepare_no',
  'VARCHAR(64) DEFAULT NULL COMMENT ''价格准备批次号'' AFTER pricing_month'
);

CALL v166_add_column_if_not_exists(
  'lp_cost_run_result',
  'price_type_confirm_no',
  'VARCHAR(64) DEFAULT NULL COMMENT ''价格类型确认批次号'' AFTER price_prepare_no'
);

CALL v166_add_column_if_not_exists(
  'lp_cost_run_result',
  'result_status',
  'VARCHAR(32) DEFAULT NULL COMMENT ''工作台成本结果状态'' AFTER price_type_confirm_no'
);

CALL v166_add_column_if_not_exists(
  'lp_cost_run_part_item',
  'oa_form_item_id',
  'BIGINT DEFAULT NULL COMMENT ''OA产品明细行ID'' AFTER oa_no'
);

CALL v166_add_column_if_not_exists(
  'lp_cost_run_part_item',
  'cost_run_version_id',
  'BIGINT DEFAULT NULL COMMENT ''成本试算版本ID'' AFTER oa_form_item_id'
);

CALL v166_add_column_if_not_exists(
  'lp_cost_run_part_item',
  'cost_run_no',
  'VARCHAR(64) DEFAULT NULL COMMENT ''试算批次号'' AFTER cost_run_version_id'
);

CALL v166_add_column_if_not_exists(
  'lp_cost_run_part_item',
  'bom_row_id',
  'BIGINT DEFAULT NULL COMMENT ''报价BOM行ID'' AFTER cost_run_no'
);

CALL v166_add_column_if_not_exists(
  'lp_cost_run_part_item',
  'price_prepare_item_id',
  'BIGINT DEFAULT NULL COMMENT ''价格准备明细ID'' AFTER bom_row_id'
);

CALL v166_add_column_if_not_exists(
  'lp_cost_run_cost_item',
  'oa_form_item_id',
  'BIGINT DEFAULT NULL COMMENT ''OA产品明细行ID'' AFTER oa_no'
);

CALL v166_add_column_if_not_exists(
  'lp_cost_run_cost_item',
  'cost_run_version_id',
  'BIGINT DEFAULT NULL COMMENT ''成本试算版本ID'' AFTER oa_form_item_id'
);

CALL v166_add_column_if_not_exists(
  'lp_cost_run_cost_item',
  'cost_run_no',
  'VARCHAR(64) DEFAULT NULL COMMENT ''试算批次号'' AFTER cost_run_version_id'
);

CALL v166_add_column_if_not_exists(
  'lp_cost_run_task',
  'cost_run_version_id',
  'BIGINT DEFAULT NULL COMMENT ''成本试算版本ID'' AFTER batch_no'
);

CALL v166_add_column_if_not_exists(
  'lp_cost_run_task',
  'cost_run_no',
  'VARCHAR(64) DEFAULT NULL COMMENT ''试算批次号'' AFTER cost_run_version_id'
);

CALL v166_drop_index_if_exists('lp_cost_run_result', 'uk_cost_run_result');
CALL v166_add_index_if_not_exists(
  'lp_cost_run_result',
  'uk_cost_run_result_quote_run',
  'UNIQUE KEY uk_cost_run_result_quote_run (oa_no, oa_form_item_id, product_code, period, cost_run_no)'
);

CALL v166_add_index_if_not_exists(
  'lp_cost_run_result',
  'idx_cost_result_quote_run',
  'KEY idx_cost_result_quote_run (oa_no, oa_form_item_id, product_code, period, cost_run_no)'
);

CALL v166_add_index_if_not_exists(
  'lp_cost_run_part_item',
  'idx_cost_part_quote_run',
  'KEY idx_cost_part_quote_run (oa_no, oa_form_item_id, product_code, cost_run_no)'
);

CALL v166_add_index_if_not_exists(
  'lp_cost_run_cost_item',
  'idx_cost_item_quote_run',
  'KEY idx_cost_item_quote_run (oa_no, oa_form_item_id, product_code, cost_run_no)'
);

CALL v166_add_index_if_not_exists(
  'lp_cost_run_task',
  'idx_cost_run_task_version',
  'KEY idx_cost_run_task_version (cost_run_no, cost_run_version_id)'
);

DROP PROCEDURE IF EXISTS v166_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v166_add_index_if_not_exists;
DROP PROCEDURE IF EXISTS v166_drop_index_if_exists;
