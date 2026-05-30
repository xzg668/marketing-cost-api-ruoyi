-- =============================================================================
-- V126: 月度调价核心表
-- -----------------------------------------------------------------------------
-- 目标：
--   1. 月度调价结果与日常 OA 成本核算结果分表存储，避免污染 lp_cost_run_result。
--   2. 以 reprice_no + calc_object_key 隔离每次调价批次的核算对象。
--   3. 保留结果主表、部品明细、成本项明细，支撑后续查询下钻和 BI。
--   4. 给 lp_factor_adjust_batch 增加 adjust_type，区分普通维护和月度调价。
-- =============================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS add_column_if_not_exists_v126;
DELIMITER //
CREATE PROCEDURE add_column_if_not_exists_v126(
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

DROP PROCEDURE IF EXISTS add_index_if_not_exists_v126;
DELIMITER //
CREATE PROCEDURE add_index_if_not_exists_v126(
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

CREATE TABLE IF NOT EXISTS lp_monthly_reprice_batch (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  reprice_no VARCHAR(64) NOT NULL COMMENT '月度调价批次号',
  pricing_month VARCHAR(7) NOT NULL COMMENT '调价月份 YYYY-MM',
  business_unit_type VARCHAR(32) NOT NULL COMMENT '业务单元',
  adjust_batch_id BIGINT NOT NULL COMMENT '影响因素调价批次ID lp_factor_adjust_batch.id',
  execution_backend VARCHAR(32) NOT NULL DEFAULT 'LOCAL_WORKER' COMMENT '执行后端：LOCAL_WORKER/EASYDATA',
  status VARCHAR(32) NOT NULL DEFAULT 'CREATED' COMMENT '状态：CREATED/PREPARING/RUNNING/WAIT_CONFIRM/CONFIRMED/FAILED/CANCELLED',
  total_count INT NOT NULL DEFAULT 0 COMMENT '应核算对象数',
  success_count INT NOT NULL DEFAULT 0 COMMENT '成功数',
  failed_count INT NOT NULL DEFAULT 0 COMMENT '失败数',
  skipped_count INT NOT NULL DEFAULT 0 COMMENT '跳过数',
  cost_engine_version VARCHAR(64) DEFAULT NULL COMMENT '成本引擎版本',
  price_version VARCHAR(64) DEFAULT NULL COMMENT '价格版本',
  rule_version VARCHAR(64) DEFAULT NULL COMMENT '规则或公式版本',
  created_by VARCHAR(64) DEFAULT NULL COMMENT '发起人ID',
  created_name VARCHAR(128) DEFAULT NULL COMMENT '发起人姓名',
  confirmed_by VARCHAR(64) DEFAULT NULL COMMENT '确认人ID',
  confirmed_name VARCHAR(128) DEFAULT NULL COMMENT '确认人姓名',
  started_at DATETIME DEFAULT NULL COMMENT '开始时间',
  finished_at DATETIME DEFAULT NULL COMMENT '计算完成时间',
  confirmed_at DATETIME DEFAULT NULL COMMENT '确认时间',
  remark VARCHAR(1000) DEFAULT NULL COMMENT '备注',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_monthly_reprice_no (reprice_no),
  KEY idx_monthly_reprice_month_bu_status (pricing_month, business_unit_type, status),
  KEY idx_monthly_reprice_bu_status (business_unit_type, status),
  KEY idx_monthly_reprice_adjust_batch (adjust_batch_id),
  KEY idx_monthly_reprice_confirmed (pricing_month, business_unit_type, confirmed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='月度调价批次表';

CREATE TABLE IF NOT EXISTS lp_monthly_reprice_task (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  reprice_no VARCHAR(64) NOT NULL COMMENT '月度调价批次号',
  pricing_month VARCHAR(7) NOT NULL COMMENT '调价月份 YYYY-MM',
  business_unit_type VARCHAR(32) NOT NULL COMMENT '业务单元',
  oa_no VARCHAR(64) NOT NULL COMMENT 'OA单号',
  oa_form_item_id BIGINT DEFAULT NULL COMMENT 'OA明细行ID oa_form_item.id',
  product_code VARCHAR(64) NOT NULL COMMENT '产品料号',
  package_method VARCHAR(128) DEFAULT NULL COMMENT '包装方式',
  customer_name VARCHAR(255) DEFAULT NULL COMMENT '客户名称',
  normalized_customer_name VARCHAR(255) DEFAULT NULL COMMENT '标准化客户名称',
  calc_object_key VARCHAR(128) NOT NULL COMMENT '核算对象唯一键',
  source_oa_calc_status VARCHAR(32) DEFAULT NULL COMMENT '来源OA核算状态',
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '任务状态：PENDING/RUNNING/SUCCESS/FAILED/CANCELLED',
  worker_id VARCHAR(128) DEFAULT NULL COMMENT '领取任务的Worker',
  locked_at DATETIME DEFAULT NULL COMMENT '领取时间',
  lock_expire_time DATETIME DEFAULT NULL COMMENT '锁过期时间',
  retry_count INT NOT NULL DEFAULT 0 COMMENT '重试次数',
  last_error_code VARCHAR(64) DEFAULT NULL COMMENT '最近错误编码',
  last_error_message VARCHAR(1000) DEFAULT NULL COMMENT '最近错误摘要',
  started_at DATETIME DEFAULT NULL COMMENT '任务开始时间',
  finished_at DATETIME DEFAULT NULL COMMENT '任务结束时间',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_reprice_task_object (reprice_no, calc_object_key),
  KEY idx_reprice_task_claim (status, lock_expire_time, reprice_no),
  KEY idx_reprice_task_batch (reprice_no, status),
  KEY idx_reprice_task_oa (reprice_no, oa_no),
  KEY idx_reprice_task_product (reprice_no, product_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='月度调价核算任务表';

CREATE TABLE IF NOT EXISTS lp_monthly_reprice_result (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  reprice_no VARCHAR(64) NOT NULL COMMENT '月度调价批次号',
  pricing_month VARCHAR(7) NOT NULL COMMENT '调价月份 YYYY-MM',
  business_unit_type VARCHAR(32) NOT NULL COMMENT '业务单元',
  oa_no VARCHAR(64) NOT NULL COMMENT 'OA单号',
  oa_form_item_id BIGINT DEFAULT NULL COMMENT 'OA明细行ID oa_form_item.id',
  product_code VARCHAR(64) NOT NULL COMMENT '产品料号',
  package_method VARCHAR(128) DEFAULT NULL COMMENT '包装方式',
  customer_name VARCHAR(255) DEFAULT NULL COMMENT '客户名称',
  calc_object_key VARCHAR(128) NOT NULL COMMENT '核算对象唯一键',
  total_cost DECIMAL(20,6) DEFAULT NULL COMMENT '成本合计',
  material_cost DECIMAL(20,6) DEFAULT NULL COMMENT '材料费',
  labor_cost DECIMAL(20,6) DEFAULT NULL COMMENT '人工费',
  auxiliary_cost DECIMAL(20,6) DEFAULT NULL COMMENT '辅料费',
  manufacturing_cost DECIMAL(20,6) DEFAULT NULL COMMENT '制造费用',
  management_cost DECIMAL(20,6) DEFAULT NULL COMMENT '管理费用',
  sales_cost DECIMAL(20,6) DEFAULT NULL COMMENT '销售费用',
  finance_cost DECIMAL(20,6) DEFAULT NULL COMMENT '财务费用',
  cost_engine_version VARCHAR(64) DEFAULT NULL COMMENT '成本引擎版本',
  price_version VARCHAR(64) DEFAULT NULL COMMENT '价格版本',
  rule_version VARCHAR(64) DEFAULT NULL COMMENT '规则版本',
  source_cost_result_id BIGINT DEFAULT NULL COMMENT '原OA成本结果ID lp_cost_run_result.id',
  calc_status VARCHAR(32) NOT NULL DEFAULT 'SUCCESS' COMMENT '计算状态：SUCCESS/FAILED',
  calc_message VARCHAR(1000) DEFAULT NULL COMMENT '计算说明',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_reprice_result_object (reprice_no, calc_object_key),
  KEY idx_reprice_result_month_bu (pricing_month, business_unit_type),
  KEY idx_reprice_result_batch_oa (reprice_no, oa_no),
  KEY idx_reprice_result_product (reprice_no, product_code),
  KEY idx_reprice_result_customer (reprice_no, customer_name),
  KEY idx_reprice_result_source_result (source_cost_result_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='月度调价成本核算结果表';

CREATE TABLE IF NOT EXISTS lp_monthly_reprice_part_item (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  reprice_no VARCHAR(64) NOT NULL COMMENT '月度调价批次号',
  pricing_month VARCHAR(7) NOT NULL COMMENT '调价月份 YYYY-MM',
  business_unit_type VARCHAR(32) NOT NULL COMMENT '业务单元',
  oa_no VARCHAR(64) NOT NULL COMMENT 'OA单号',
  calc_object_key VARCHAR(128) NOT NULL COMMENT '核算对象唯一键',
  product_code VARCHAR(64) NOT NULL COMMENT '产品料号',
  package_method VARCHAR(128) DEFAULT NULL COMMENT '包装方式',
  customer_name VARCHAR(255) DEFAULT NULL COMMENT '客户名称',
  line_no INT DEFAULT NULL COMMENT '明细行号',
  part_code VARCHAR(64) DEFAULT NULL COMMENT '部品或物料编码',
  part_name VARCHAR(255) DEFAULT NULL COMMENT '部品或物料名称',
  part_drawing_no VARCHAR(255) DEFAULT NULL COMMENT '部品图号',
  material VARCHAR(128) DEFAULT NULL COMMENT '材质',
  shape_attr VARCHAR(64) DEFAULT NULL COMMENT '形态属性',
  quantity DECIMAL(20,8) DEFAULT NULL COMMENT '用量',
  unit_price DECIMAL(20,8) DEFAULT NULL COMMENT '单价',
  amount DECIMAL(20,6) DEFAULT NULL COMMENT '金额',
  price_source VARCHAR(128) DEFAULT NULL COMMENT '取价来源',
  price_source_id BIGINT DEFAULT NULL COMMENT '取价来源ID',
  linked_calc_item_id BIGINT DEFAULT NULL COMMENT '联动价计算结果ID lp_price_linked_calc_item.id',
  calc_status VARCHAR(32) NOT NULL DEFAULT 'SUCCESS' COMMENT '计算状态：SUCCESS/FAILED',
  calc_message VARCHAR(1000) DEFAULT NULL COMMENT '计算说明',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_monthly_part_object (reprice_no, calc_object_key),
  KEY idx_monthly_part_part (reprice_no, part_code),
  KEY idx_monthly_part_oa (reprice_no, oa_no),
  KEY idx_monthly_part_linked_calc (linked_calc_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='月度调价部品明细表';

CREATE TABLE IF NOT EXISTS lp_monthly_reprice_cost_item (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  reprice_no VARCHAR(64) NOT NULL COMMENT '月度调价批次号',
  pricing_month VARCHAR(7) NOT NULL COMMENT '调价月份 YYYY-MM',
  business_unit_type VARCHAR(32) NOT NULL COMMENT '业务单元',
  oa_no VARCHAR(64) NOT NULL COMMENT 'OA单号',
  calc_object_key VARCHAR(128) NOT NULL COMMENT '核算对象唯一键',
  product_code VARCHAR(64) NOT NULL COMMENT '产品料号',
  package_method VARCHAR(128) DEFAULT NULL COMMENT '包装方式',
  customer_name VARCHAR(255) DEFAULT NULL COMMENT '客户名称',
  line_no INT DEFAULT NULL COMMENT '成本项行号',
  cost_item_code VARCHAR(64) NOT NULL COMMENT '成本项编码',
  cost_item_name VARCHAR(128) DEFAULT NULL COMMENT '成本项名称',
  base_amount DECIMAL(20,6) DEFAULT NULL COMMENT '计算基数',
  rate DECIMAL(18,8) DEFAULT NULL COMMENT '费率或系数',
  amount DECIMAL(20,6) DEFAULT NULL COMMENT '金额',
  calc_formula VARCHAR(1000) DEFAULT NULL COMMENT '计算公式或说明',
  calc_status VARCHAR(32) NOT NULL DEFAULT 'SUCCESS' COMMENT '计算状态：SUCCESS/FAILED',
  calc_message VARCHAR(1000) DEFAULT NULL COMMENT '计算说明',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_monthly_cost_item (reprice_no, calc_object_key, cost_item_code),
  KEY idx_monthly_cost_object (reprice_no, calc_object_key),
  KEY idx_monthly_cost_item (reprice_no, cost_item_code),
  KEY idx_monthly_cost_oa (reprice_no, oa_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='月度调价成本项明细表';

CREATE TABLE IF NOT EXISTS lp_monthly_reprice_audit_log (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  reprice_no VARCHAR(64) DEFAULT NULL COMMENT '月度调价批次号',
  pricing_month VARCHAR(7) DEFAULT NULL COMMENT '调价月份 YYYY-MM',
  business_unit_type VARCHAR(32) DEFAULT NULL COMMENT '业务单元',
  operation_type VARCHAR(64) NOT NULL COMMENT '操作类型',
  operation_name VARCHAR(128) NOT NULL COMMENT '操作名称',
  operator_id VARCHAR(64) DEFAULT NULL COMMENT '操作人ID',
  operator_name VARCHAR(128) DEFAULT NULL COMMENT '操作人姓名',
  operator_role VARCHAR(128) DEFAULT NULL COMMENT '操作人角色',
  operation_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  target_type VARCHAR(64) DEFAULT NULL COMMENT '操作对象类型',
  target_id VARCHAR(64) DEFAULT NULL COMMENT '操作对象ID',
  target_key VARCHAR(255) DEFAULT NULL COMMENT '操作对象业务键',
  before_json JSON DEFAULT NULL COMMENT '操作前快照',
  after_json JSON DEFAULT NULL COMMENT '操作后快照',
  change_summary VARCHAR(1000) DEFAULT NULL COMMENT '变更摘要',
  request_ip VARCHAR(64) DEFAULT NULL COMMENT '请求IP',
  request_user_agent VARCHAR(512) DEFAULT NULL COMMENT '请求UA',
  request_id VARCHAR(128) DEFAULT NULL COMMENT '请求链路ID',
  remark VARCHAR(1000) DEFAULT NULL COMMENT '备注',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_monthly_audit_reprice (reprice_no, operation_time),
  KEY idx_monthly_audit_month_bu (pricing_month, business_unit_type, operation_time),
  KEY idx_monthly_audit_operator (operator_id, operation_time),
  KEY idx_monthly_audit_target (target_type, target_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='月度调价审计日志表';

CALL add_column_if_not_exists_v126('lp_factor_adjust_batch', 'adjust_type',
  'VARCHAR(16) NOT NULL DEFAULT ''NORMAL'' COMMENT ''调整类型：NORMAL普通维护/MONTHLY月度调价'' AFTER `adjust_batch_no`');

CALL add_index_if_not_exists_v126('lp_factor_adjust_batch', 'idx_factor_adjust_type_context',
  'KEY idx_factor_adjust_type_context (adjust_type, business_unit_type, pricing_month, status, deleted)');

UPDATE lp_factor_adjust_batch
   SET adjust_type = 'NORMAL'
 WHERE adjust_type IS NULL OR adjust_type = '';

DROP PROCEDURE IF EXISTS add_index_if_not_exists_v126;
DROP PROCEDURE IF EXISTS add_column_if_not_exists_v126;

