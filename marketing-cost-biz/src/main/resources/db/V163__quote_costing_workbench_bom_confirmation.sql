-- =============================================================================
-- V163: 单产品核算工作台 BOM 确认批次
-- -----------------------------------------------------------------------------
-- QWB-01-01：按 OA + 产品行 + 顶层料号 + 核算月固化报价 BOM 确认状态。
-- =============================================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS lp_quote_bom_confirmation (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  confirm_no VARCHAR(64) NOT NULL COMMENT 'BOM确认批次号',
  oa_no VARCHAR(64) NOT NULL COMMENT 'OA单号',
  oa_form_item_id BIGINT NOT NULL COMMENT 'OA产品明细行ID',
  top_product_code VARCHAR(64) NOT NULL COMMENT '顶层产品料号',
  period_month VARCHAR(7) NOT NULL COMMENT '核算月份 YYYY-MM',
  confirm_status VARCHAR(32) NOT NULL DEFAULT 'CONFIRMED' COMMENT '确认状态：CONFIRMED/INVALID/STALE',
  confirm_version INT NOT NULL DEFAULT 1 COMMENT '确认版本号',
  row_count INT NOT NULL DEFAULT 0 COMMENT '确认时BOM行数',
  manual_modified_count INT NOT NULL DEFAULT 0 COMMENT '人工修改行数',
  replace_count INT NOT NULL DEFAULT 0 COMMENT '替换料号行数',
  usage_adjust_count INT NOT NULL DEFAULT 0 COMMENT '用量调整行数',
  confirmed_by VARCHAR(64) DEFAULT NULL COMMENT '确认人',
  confirmed_at DATETIME DEFAULT NULL COMMENT '确认时间',
  confirm_remark VARCHAR(1000) DEFAULT NULL COMMENT '确认备注',
  business_unit_type VARCHAR(32) DEFAULT NULL COMMENT '业务单元类型',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_quote_bom_confirm_no (confirm_no),
  KEY idx_quote_bom_confirm_item (oa_no, oa_form_item_id, top_product_code, period_month),
  KEY idx_quote_bom_confirm_status (confirm_status),
  KEY idx_quote_bom_confirm_bu (business_unit_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='报价单产品核算工作台BOM确认批次表';

CREATE TABLE IF NOT EXISTS lp_quote_bom_confirmation_log (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  confirm_no VARCHAR(64) NOT NULL COMMENT 'BOM确认批次号',
  oa_no VARCHAR(64) NOT NULL COMMENT 'OA单号',
  oa_form_item_id BIGINT NOT NULL COMMENT 'OA产品明细行ID',
  top_product_code VARCHAR(64) NOT NULL COMMENT '顶层产品料号',
  period_month VARCHAR(7) NOT NULL COMMENT '核算月份 YYYY-MM',
  action_type VARCHAR(32) NOT NULL COMMENT '动作类型：CONFIRM/CANCEL/STALE',
  before_status VARCHAR(32) DEFAULT NULL COMMENT '变更前状态',
  after_status VARCHAR(32) DEFAULT NULL COMMENT '变更后状态',
  operator_id VARCHAR(64) DEFAULT NULL COMMENT '操作人',
  operated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  remark VARCHAR(1000) DEFAULT NULL COMMENT '操作备注',
  business_unit_type VARCHAR(32) DEFAULT NULL COMMENT '业务单元类型',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_quote_bom_confirm_log_confirm (confirm_no),
  KEY idx_quote_bom_confirm_log_item (oa_no, oa_form_item_id, top_product_code, period_month),
  KEY idx_quote_bom_confirm_log_action (action_type),
  KEY idx_quote_bom_confirm_log_bu (business_unit_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='报价单产品核算工作台BOM确认日志表';
