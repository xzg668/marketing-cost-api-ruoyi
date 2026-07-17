-- =============================================================================
-- V187: 财务 Cu 基准价与 OA 锁价双场景数据契约
-- -----------------------------------------------------------------------------
-- FCQ-01 仅落库结构、历史回填和查询索引，不包含双场景生成或差额计算逻辑。
-- =============================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS v187_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v187_add_index_if_not_exists;

DELIMITER $$

CREATE PROCEDURE v187_add_column_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_column_name VARCHAR(64),
  IN p_column_definition TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND COLUMN_NAME = p_column_name
  ) THEN
    SET @ddl = CONCAT(
      'ALTER TABLE `', p_table_name, '` ADD COLUMN `', p_column_name, '` ',
      p_column_definition);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END$$

CREATE PROCEDURE v187_add_index_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_index_name VARCHAR(64),
  IN p_index_definition TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
      FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND INDEX_NAME = p_index_name
  ) THEN
    SET @ddl = CONCAT(
      'ALTER TABLE `', p_table_name, '` ADD ', p_index_definition);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END$$

DELIMITER ;

-- 价格准备批次：历史批次统一视为 OA 锁价场景。
CALL v187_add_column_if_not_exists(
  'lp_price_prepare_batch',
  'scenario_type',
  'VARCHAR(32) NOT NULL DEFAULT ''OA_LOCKED'' COMMENT ''计价场景：OA_LOCKED/FINANCE_QUOTE_BASE'' AFTER `source_type`'
);
CALL v187_add_column_if_not_exists(
  'lp_price_prepare_batch',
  'scenario_group_no',
  'VARCHAR(64) DEFAULT NULL COMMENT ''同一组双场景价格准备编号'' AFTER `scenario_type`'
);
CALL v187_add_column_if_not_exists(
  'lp_price_prepare_batch',
  'source_prepare_no',
  'VARCHAR(64) DEFAULT NULL COMMENT ''财务场景引用的原 OA 价格准备批次'' AFTER `scenario_group_no`'
);

UPDATE lp_price_prepare_batch
   SET scenario_type = 'OA_LOCKED'
 WHERE scenario_type IS NULL
    OR TRIM(scenario_type) = '';

ALTER TABLE lp_price_prepare_batch
  MODIFY COLUMN scenario_type VARCHAR(32) NOT NULL DEFAULT 'OA_LOCKED'
    COMMENT '计价场景：OA_LOCKED/FINANCE_QUOTE_BASE';

CALL v187_add_index_if_not_exists(
  'lp_price_prepare_batch',
  'idx_price_prepare_scenario',
  'KEY idx_price_prepare_scenario (oa_no, oa_form_item_id, top_product_code, period_month, scenario_type)'
);
CALL v187_add_index_if_not_exists(
  'lp_price_prepare_batch',
  'idx_price_prepare_scenario_group',
  'KEY idx_price_prepare_scenario_group (scenario_group_no, scenario_type)'
);

-- 价格准备明细：历史行允许 settlement_key 为空。
CALL v187_add_column_if_not_exists(
  'lp_price_prepare_item',
  'settlement_key',
  'VARCHAR(192) DEFAULT NULL COMMENT ''跨计价场景保持不变的稳定结算键'' AFTER `current_flag`'
);
CALL v187_add_index_if_not_exists(
  'lp_price_prepare_item',
  'idx_price_prepare_item_settlement',
  'KEY idx_price_prepare_item_settlement (prepare_no, settlement_key)'
);

-- 一个成本版本下两个材料计价场景的汇总快照。
CREATE TABLE IF NOT EXISTS lp_quote_cost_price_scenario (
  id BIGINT NOT NULL AUTO_INCREMENT,
  scenario_no VARCHAR(64) NOT NULL,
  cost_run_version_id BIGINT NOT NULL,
  cost_run_no VARCHAR(64) NOT NULL,
  scenario_type VARCHAR(32) NOT NULL COMMENT 'OA_LOCKED/FINANCE_QUOTE_BASE',
  price_prepare_no VARCHAR(64) NOT NULL,
  pricing_month VARCHAR(7) NOT NULL,
  cu_price DECIMAL(20,8) DEFAULT NULL COMMENT '元/公斤',
  cu_price_source VARCHAR(64) DEFAULT NULL,
  cu_source_ref_id BIGINT DEFAULT NULL COMMENT '财务基价 ID；OA 场景可为空',
  material_cost DECIMAL(20,8) DEFAULT NULL,
  total_cost DECIMAL(20,8) DEFAULT NULL COMMENT '只有 FINANCE_QUOTE_BASE 需要完整总成本',
  input_snapshot_hash VARCHAR(128) DEFAULT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'SUCCESS',
  message VARCHAR(1000) DEFAULT NULL,
  business_unit_type VARCHAR(32) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_quote_cost_scenario_no (scenario_no),
  UNIQUE KEY uk_quote_cost_version_scenario (cost_run_version_id, scenario_type),
  KEY idx_quote_cost_scenario_prepare (price_prepare_no),
  KEY idx_quote_cost_scenario_run (cost_run_no, scenario_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='报价成本版本双计价场景汇总';

-- 逐结算键保存财务基准与 OA 锁价金额及有符号差额。
CREATE TABLE IF NOT EXISTS lp_quote_cu_material_diff_item (
  id BIGINT NOT NULL AUTO_INCREMENT,
  cost_run_version_id BIGINT NOT NULL,
  cost_run_no VARCHAR(64) NOT NULL,
  line_no INT NOT NULL,
  settlement_key VARCHAR(192) NOT NULL COMMENT '稳定结算或组件键',
  parent_settlement_key VARCHAR(192) DEFAULT NULL COMMENT '组件行所属的结算层键',
  detail_level VARCHAR(32) NOT NULL DEFAULT 'SETTLEMENT'
    COMMENT 'SETTLEMENT/RAW_COMPONENT',
  contributes_to_adjustment TINYINT NOT NULL DEFAULT 1
    COMMENT '1 参与 Cu 差额汇总；0 仅用于解释',
  bom_row_id BIGINT DEFAULT NULL,
  top_product_code VARCHAR(64) NOT NULL,
  parent_material_code VARCHAR(64) DEFAULT NULL,
  material_code VARCHAR(64) NOT NULL,
  material_name VARCHAR(180) DEFAULT NULL,
  item_type VARCHAR(32) DEFAULT NULL,
  quantity DECIMAL(20,8) DEFAULT NULL,
  finance_prepare_item_id BIGINT DEFAULT NULL,
  oa_prepare_item_id BIGINT DEFAULT NULL,
  finance_unit_price DECIMAL(20,8) DEFAULT NULL,
  oa_unit_price DECIMAL(20,8) DEFAULT NULL,
  finance_amount DECIMAL(20,8) DEFAULT NULL,
  oa_amount DECIMAL(20,8) DEFAULT NULL,
  diff_amount DECIMAL(20,8) NOT NULL,
  cu_affected TINYINT NOT NULL DEFAULT 0,
  price_formula_ref_type VARCHAR(64) DEFAULT NULL,
  price_formula_ref_id BIGINT DEFAULT NULL,
  trace_json JSON DEFAULT NULL,
  business_unit_type VARCHAR(32) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_quote_cu_diff_line (cost_run_version_id, settlement_key),
  KEY idx_quote_cu_diff_material (material_code),
  KEY idx_quote_cu_diff_parent (parent_material_code),
  KEY idx_quote_cu_diff_parent_key (cost_run_version_id, parent_settlement_key),
  KEY idx_quote_cu_diff_run (cost_run_no, line_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='报价 Cu 基准与 OA 锁价材料费差异明细';

-- 成本版本保存双价格批次、Cu 价格与最终报价汇总。
CALL v187_add_column_if_not_exists(
  'lp_quote_cost_run_version',
  'oa_price_prepare_no',
  'VARCHAR(64) DEFAULT NULL COMMENT ''OA 锁价价格准备批次'' AFTER `price_prepare_no`'
);
CALL v187_add_column_if_not_exists(
  'lp_quote_cost_run_version',
  'finance_price_prepare_no',
  'VARCHAR(64) DEFAULT NULL COMMENT ''财务基准价格准备批次'' AFTER `oa_price_prepare_no`'
);
CALL v187_add_column_if_not_exists(
  'lp_quote_cost_run_version',
  'finance_cu_price',
  'DECIMAL(20,8) DEFAULT NULL COMMENT ''财务 Cu 基准价，元/公斤'' AFTER `finance_price_prepare_no`'
);
CALL v187_add_column_if_not_exists(
  'lp_quote_cost_run_version',
  'oa_cu_price',
  'DECIMAL(20,8) DEFAULT NULL COMMENT ''OA Cu 锁价，元/公斤'' AFTER `finance_cu_price`'
);
CALL v187_add_column_if_not_exists(
  'lp_quote_cost_run_version',
  'finance_base_price_id',
  'BIGINT DEFAULT NULL COMMENT ''财务 Cu 基价记录 ID'' AFTER `oa_cu_price`'
);
CALL v187_add_column_if_not_exists(
  'lp_quote_cost_run_version',
  'finance_material_cost',
  'DECIMAL(20,8) DEFAULT NULL COMMENT ''财务基准材料费'' AFTER `finance_base_price_id`'
);
CALL v187_add_column_if_not_exists(
  'lp_quote_cost_run_version',
  'oa_material_cost',
  'DECIMAL(20,8) DEFAULT NULL COMMENT ''OA 锁价材料费'' AFTER `finance_material_cost`'
);
CALL v187_add_column_if_not_exists(
  'lp_quote_cost_run_version',
  'cu_material_adjustment',
  'DECIMAL(20,8) DEFAULT NULL COMMENT ''OA 材料费减财务材料费的有符号差额'' AFTER `oa_material_cost`'
);
CALL v187_add_column_if_not_exists(
  'lp_quote_cost_run_version',
  'final_quote_amount',
  'DECIMAL(20,8) DEFAULT NULL COMMENT ''财务总成本加 Cu 材料费差额'' AFTER `cu_material_adjustment`'
);
CALL v187_add_index_if_not_exists(
  'lp_quote_cost_run_version',
  'idx_quote_cost_oa_prepare',
  'KEY idx_quote_cost_oa_prepare (oa_price_prepare_no)'
);
CALL v187_add_index_if_not_exists(
  'lp_quote_cost_run_version',
  'idx_quote_cost_finance_prepare',
  'KEY idx_quote_cost_finance_prepare (finance_price_prepare_no)'
);
CALL v187_add_index_if_not_exists(
  'lp_quote_cost_run_version',
  'idx_quote_cost_finance_base',
  'KEY idx_quote_cost_finance_base (finance_base_price_id)'
);

-- 成本结果表冗余最终报价汇总，供一览和导出直接查询。
CALL v187_add_column_if_not_exists(
  'lp_cost_run_result',
  'finance_material_cost',
  'DECIMAL(20,8) DEFAULT NULL COMMENT ''财务基准材料费'' AFTER `total_cost`'
);
CALL v187_add_column_if_not_exists(
  'lp_cost_run_result',
  'oa_material_cost',
  'DECIMAL(20,8) DEFAULT NULL COMMENT ''OA 锁价材料费'' AFTER `finance_material_cost`'
);
CALL v187_add_column_if_not_exists(
  'lp_cost_run_result',
  'cu_material_adjustment',
  'DECIMAL(20,8) DEFAULT NULL COMMENT ''OA 材料费减财务材料费的有符号差额'' AFTER `oa_material_cost`'
);
CALL v187_add_column_if_not_exists(
  'lp_cost_run_result',
  'final_quote_amount',
  'DECIMAL(20,8) DEFAULT NULL COMMENT ''财务总成本加 Cu 材料费差额'' AFTER `cu_material_adjustment`'
);

DROP PROCEDURE IF EXISTS v187_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v187_add_index_if_not_exists;
