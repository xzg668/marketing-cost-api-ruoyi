-- =============================================================================
-- V108: 价格准备批次、明细和缺口表
-- -----------------------------------------------------------------------------
-- 说明：
--   1. 价格准备是实时成本计算前的显式准备步骤，只消费 BOM 结算行，不改写 BOM。
--   2. 当前阶段只沉淀缺价、缺结构、缺主档等缺口，不触发 OA 推送，也不正式阻断成本计算。
--   3. 普通料号、包装组件、自制件都统一写入本批次结果，便于实时成本只消费准备结果。
-- =============================================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS lp_price_prepare_batch (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  prepare_no VARCHAR(64) NOT NULL COMMENT '价格准备批次号',
  oa_no VARCHAR(64) NOT NULL COMMENT 'OA单号',
  period_month VARCHAR(7) NOT NULL COMMENT '价格期间 YYYY-MM',
  bom_purpose VARCHAR(64) NOT NULL DEFAULT '主制造' COMMENT 'BOM目的，第一版固定主制造',
  source_type VARCHAR(32) NOT NULL DEFAULT 'U9' COMMENT 'BOM来源类型，第一版固定或默认U9',
  status VARCHAR(32) NOT NULL DEFAULT 'RUNNING' COMMENT '状态：RUNNING/SUCCESS/PARTIAL/FAILED',
  total_count INT NOT NULL DEFAULT 0 COMMENT 'BOM结算明细总数',
  success_count INT NOT NULL DEFAULT 0 COMMENT '准备成功数',
  warning_count INT NOT NULL DEFAULT 0 COMMENT '有警告但可继续数',
  gap_count INT NOT NULL DEFAULT 0 COMMENT '缺价/缺资料数',
  started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '开始时间',
  finished_at DATETIME DEFAULT NULL COMMENT '结束时间',
  message VARCHAR(1000) DEFAULT NULL COMMENT '批次摘要',
  business_unit_type VARCHAR(32) DEFAULT NULL COMMENT '业务单元类型',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_price_prepare_no (prepare_no),
  KEY idx_price_prepare_oa_period (oa_no, period_month),
  KEY idx_price_prepare_status (status),
  KEY idx_price_prepare_started (started_at),
  KEY idx_price_prepare_bu (business_unit_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='价格准备批次表';

CREATE TABLE IF NOT EXISTS lp_price_prepare_item (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  prepare_no VARCHAR(64) NOT NULL COMMENT '价格准备批次号',
  oa_no VARCHAR(64) NOT NULL COMMENT 'OA单号',
  top_product_code VARCHAR(64) NOT NULL COMMENT '顶级成品料号',
  bom_row_id BIGINT DEFAULT NULL COMMENT 'BOM结算行ID lp_bom_costing_row.id',
  material_code VARCHAR(64) NOT NULL COMMENT '结算明细料号',
  material_name VARCHAR(180) DEFAULT NULL COMMENT '料号名称',
  item_type VARCHAR(32) NOT NULL COMMENT '料号类型：NORMAL/PACKAGE_COMPONENT/MAKE_PART',
  quantity DECIMAL(20,8) DEFAULT NULL COMMENT 'BOM结算数量',
  unit_price DECIMAL(20,8) DEFAULT NULL COMMENT '准备得到的单价',
  amount DECIMAL(20,8) DEFAULT NULL COMMENT '金额：quantity * unit_price',
  price_source VARCHAR(64) DEFAULT NULL COMMENT '价格来源',
  status VARCHAR(32) NOT NULL DEFAULT 'READY' COMMENT '状态：READY/MISSING_PRICE/MISSING_STRUCTURE/MISSING_MASTER/MISSING_PRICE_TYPE/FAILED',
  result_ref_type VARCHAR(64) DEFAULT NULL COMMENT '结果来源类型：PACKAGE_PRICE/MAKE_PART_PRICE/FIXED_PRICE/RANGE_PRICE/LINKED_PRICE等',
  result_ref_id BIGINT DEFAULT NULL COMMENT '关联价格结果ID',
  message VARCHAR(1000) DEFAULT NULL COMMENT '明细说明',
  business_unit_type VARCHAR(32) DEFAULT NULL COMMENT '业务单元类型',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_price_prepare_item_row (prepare_no, bom_row_id, material_code),
  KEY idx_price_prepare_item_prepare (prepare_no),
  KEY idx_price_prepare_item_oa_top (oa_no, top_product_code),
  KEY idx_price_prepare_item_material (material_code),
  KEY idx_price_prepare_item_type_status (item_type, status),
  KEY idx_price_prepare_item_ref (result_ref_type, result_ref_id),
  KEY idx_price_prepare_item_bu (business_unit_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='价格准备明细表';

CREATE TABLE IF NOT EXISTS lp_price_prepare_gap (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  prepare_no VARCHAR(64) NOT NULL COMMENT '价格准备批次号',
  oa_no VARCHAR(64) NOT NULL COMMENT 'OA单号',
  top_product_code VARCHAR(64) DEFAULT NULL COMMENT '顶级成品料号',
  material_code VARCHAR(64) NOT NULL COMMENT '当前结算料号',
  gap_material_code VARCHAR(64) DEFAULT NULL COMMENT '真正缺数据的料号，包装组件时可能是子件',
  gap_type VARCHAR(32) NOT NULL COMMENT '缺口类型：MISSING_STRUCTURE/MISSING_PRICE_TYPE/MISSING_PRICE/MISSING_MASTER',
  item_type VARCHAR(32) NOT NULL COMMENT '料号类型：NORMAL/PACKAGE_COMPONENT/MAKE_PART',
  source_table VARCHAR(64) DEFAULT NULL COMMENT '缺口来源表或服务',
  message VARCHAR(1000) DEFAULT NULL COMMENT '给业务/技术员看的说明',
  oa_push_status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'OA推送状态预留：PENDING/PUSHED/SKIPPED',
  business_unit_type VARCHAR(32) DEFAULT NULL COMMENT '业务单元类型',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_price_prepare_gap_prepare (prepare_no),
  KEY idx_price_prepare_gap_oa_top (oa_no, top_product_code),
  KEY idx_price_prepare_gap_material (material_code),
  KEY idx_price_prepare_gap_gap_material (gap_material_code),
  KEY idx_price_prepare_gap_type (gap_type),
  KEY idx_price_prepare_gap_push (oa_push_status),
  KEY idx_price_prepare_gap_bu (business_unit_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='价格准备缺口表';
