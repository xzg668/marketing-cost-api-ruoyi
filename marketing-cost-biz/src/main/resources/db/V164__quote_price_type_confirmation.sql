-- =============================================================================
-- V164: 单产品核算工作台价格类型确认批次
-- -----------------------------------------------------------------------------
-- QWB-01-02：固化产品行取价对象及其价格类型确认结果。
-- =============================================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS lp_quote_price_type_confirm_batch (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  confirm_no VARCHAR(64) NOT NULL COMMENT '价格类型确认批次号',
  oa_no VARCHAR(64) NOT NULL COMMENT 'OA单号',
  oa_form_item_id BIGINT NOT NULL COMMENT 'OA产品明细行ID',
  product_code VARCHAR(64) NOT NULL COMMENT '产品料号',
  period_month VARCHAR(7) NOT NULL COMMENT '核算月份 YYYY-MM',
  bom_confirm_no VARCHAR(64) DEFAULT NULL COMMENT '关联BOM确认批次号',
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT/CONFIRMED/INVALID/STALE',
  total_count INT NOT NULL DEFAULT 0 COMMENT '取价对象总数',
  confirmed_count INT NOT NULL DEFAULT 0 COMMENT '已确认数量',
  gap_count INT NOT NULL DEFAULT 0 COMMENT '缺价格类型数量',
  reference_price_count INT NOT NULL DEFAULT 0 COMMENT '参考价格数量',
  confirmed_by VARCHAR(64) DEFAULT NULL COMMENT '确认人',
  confirmed_at DATETIME DEFAULT NULL COMMENT '确认时间',
  message VARCHAR(1000) DEFAULT NULL COMMENT '确认摘要',
  business_unit_type VARCHAR(32) DEFAULT NULL COMMENT '业务单元类型',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_quote_price_type_confirm_no (confirm_no),
  KEY idx_qptc_item_scope (oa_no, oa_form_item_id, product_code, period_month),
  KEY idx_qptc_status (status),
  KEY idx_qptc_bom_confirm (bom_confirm_no),
  KEY idx_qptc_bu (business_unit_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='报价单产品价格类型确认批次表';

CREATE TABLE IF NOT EXISTS lp_quote_price_type_confirm_item (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  confirm_no VARCHAR(64) NOT NULL COMMENT '价格类型确认批次号',
  oa_no VARCHAR(64) NOT NULL COMMENT 'OA单号',
  oa_form_item_id BIGINT NOT NULL COMMENT 'OA产品明细行ID',
  product_code VARCHAR(64) NOT NULL COMMENT '产品料号',
  period_month VARCHAR(7) NOT NULL COMMENT '核算月份 YYYY-MM',
  bom_row_id BIGINT DEFAULT NULL COMMENT '报价BOM行ID lp_bom_costing_row.id',
  parent_material_code VARCHAR(64) DEFAULT NULL COMMENT '父项料号',
  material_code VARCHAR(64) NOT NULL COMMENT '取价对象料号',
  material_name VARCHAR(180) DEFAULT NULL COMMENT '取价对象名称',
  object_type VARCHAR(32) NOT NULL COMMENT '取价对象类型：NORMAL/MAKE_PARENT/MAKE_RAW/MAKE_SCRAP/PACKAGE_PARENT/PACKAGE_CHILD',
  quantity DECIMAL(20,8) DEFAULT NULL COMMENT '对象数量',
  price_type VARCHAR(64) DEFAULT NULL COMMENT '价格类型',
  price_type_source VARCHAR(32) DEFAULT NULL COMMENT '价格类型来源：MATERIAL_PRICE_TYPE/SYNTHETIC/MANUAL',
  type_effective_from DATE DEFAULT NULL COMMENT '价格类型生效开始日期',
  type_effective_to DATE DEFAULT NULL COMMENT '价格类型生效结束日期',
  status VARCHAR(32) NOT NULL DEFAULT 'MISSING_TYPE' COMMENT '状态：CONFIRMED/MISSING_TYPE/CHILD_MISSING_TYPE',
  message VARCHAR(1000) DEFAULT NULL COMMENT '明细说明',
  business_unit_type VARCHAR(32) DEFAULT NULL COMMENT '业务单元类型',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_qptci_confirm (confirm_no),
  KEY idx_qptci_material (material_code),
  KEY idx_qptci_bom_row (bom_row_id),
  KEY idx_qptci_item_scope (oa_no, oa_form_item_id, product_code, period_month),
  KEY idx_qptci_status (status),
  KEY idx_qptci_bu (business_unit_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='报价单产品价格类型确认明细表';
