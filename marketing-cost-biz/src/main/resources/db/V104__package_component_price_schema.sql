-- =============================================================================
-- V104: 包装组件结构快照和价格明细
-- -----------------------------------------------------------------------------
-- 说明：
--   1. 结构按 package_material_code + period_month 月度锁定，月内不自动刷新。
--   2. 包装子件重复行需要保留，因此明细唯一键使用 snapshot_id/price_id + line_no。
--   3. 当前阶段只沉淀缺结构/缺价记录，不触发 OA 推送，也不正式阻断报价。
-- =============================================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS lp_package_component_snapshot (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  package_material_code VARCHAR(64) NOT NULL COMMENT '包装组件父料号',
  package_material_name VARCHAR(180) DEFAULT NULL COMMENT '包装组件名称',
  period_month VARCHAR(7) NOT NULL COMMENT '期间 YYYY-MM',
  status VARCHAR(32) NOT NULL DEFAULT 'NORMAL' COMMENT '状态：NORMAL/MISSING_STRUCTURE/PENDING_MAINTAIN/VOIDED',
  source_type VARCHAR(32) NOT NULL DEFAULT 'BOM' COMMENT '快照来源：BOM/MANUAL/REFERENCE',
  source_quote_no VARCHAR(64) DEFAULT NULL COMMENT '首次触发报价单号，仅追溯',
  source_oa_no VARCHAR(64) DEFAULT NULL COMMENT '首次触发OA单号，仅追溯',
  source_top_product_code VARCHAR(64) DEFAULT NULL COMMENT '来源BOM顶层产品料号，仅追溯',
  source_bom_purpose VARCHAR(64) DEFAULT NULL COMMENT '来源BOM用途，仅追溯',
  source_bom_source_type VARCHAR(32) DEFAULT NULL COMMENT '来源BOM数据源类型，如U9',
  source_as_of_date DATE DEFAULT NULL COMMENT '来源BOM版本日期',
  source_raw_hierarchy_id BIGINT DEFAULT NULL COMMENT '来源包装父节点 lp_bom_raw_hierarchy.id',
  source_path VARCHAR(1024) DEFAULT NULL COMMENT '来源包装父节点path',
  reference_package_code VARCHAR(64) DEFAULT NULL COMMENT '参考包装料号，手工/参考复制时使用',
  missing_reason VARCHAR(500) DEFAULT NULL COMMENT '缺结构原因',
  locked_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '本月结构锁定时间',
  business_unit_type VARCHAR(32) DEFAULT NULL COMMENT '业务单元类型',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pkg_snapshot_month (package_material_code, period_month),
  KEY idx_pkg_snapshot_period_status (period_month, status),
  KEY idx_pkg_snapshot_package (package_material_code),
  KEY idx_pkg_snapshot_source_top (source_top_product_code, source_bom_purpose, source_bom_source_type),
  KEY idx_pkg_snapshot_raw (source_raw_hierarchy_id),
  KEY idx_pkg_snapshot_bu (business_unit_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='包装组件月度结构快照主表';

CREATE TABLE IF NOT EXISTS lp_package_component_snapshot_detail (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  snapshot_id BIGINT NOT NULL COMMENT '结构快照主表ID',
  package_material_code VARCHAR(64) NOT NULL COMMENT '包装组件父料号',
  period_month VARCHAR(7) NOT NULL COMMENT '期间 YYYY-MM',
  line_no INT NOT NULL COMMENT '快照内行号',
  child_material_code VARCHAR(64) NOT NULL COMMENT '子件料号',
  child_material_name VARCHAR(180) DEFAULT NULL COMMENT '子件名称',
  child_material_spec VARCHAR(255) DEFAULT NULL COMMENT '子件规格',
  child_shape_attr VARCHAR(64) DEFAULT NULL COMMENT '子件形态',
  qty_per_parent DECIMAL(20,8) DEFAULT NULL COMMENT '子件相对包装父料号用量',
  qty_per_top DECIMAL(20,8) DEFAULT NULL COMMENT '来源BOM中相对顶层产品用量，仅追溯',
  source_hierarchy_id BIGINT DEFAULT NULL COMMENT '来源 lp_bom_raw_hierarchy.id',
  source_parent_code VARCHAR(64) DEFAULT NULL COMMENT '来源父料号',
  source_path VARCHAR(1024) DEFAULT NULL COMMENT '来源path',
  source_sort_seq INT DEFAULT NULL COMMENT '来源U9子件项次',
  business_unit_type VARCHAR(32) DEFAULT NULL COMMENT '业务单元类型',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pkg_snapshot_detail_line (snapshot_id, line_no),
  KEY idx_pkg_snapshot_detail_snapshot (snapshot_id),
  KEY idx_pkg_snapshot_detail_pkg_period (package_material_code, period_month),
  KEY idx_pkg_snapshot_detail_child (child_material_code),
  KEY idx_pkg_snapshot_detail_source (source_hierarchy_id),
  KEY idx_pkg_snapshot_detail_bu (business_unit_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='包装组件月度结构快照明细表';

CREATE TABLE IF NOT EXISTS lp_package_component_price (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  snapshot_id BIGINT DEFAULT NULL COMMENT '对应结构快照ID',
  package_material_code VARCHAR(64) NOT NULL COMMENT '包装组件父料号',
  package_material_name VARCHAR(180) DEFAULT NULL COMMENT '包装组件名称',
  period_month VARCHAR(7) NOT NULL COMMENT '期间 YYYY-MM',
  total_price DECIMAL(20,8) DEFAULT NULL COMMENT '包装组件单价',
  price_status VARCHAR(32) NOT NULL DEFAULT 'PRICED' COMMENT '价格状态：PRICED/MISSING_STRUCTURE/MISSING_CHILD_PRICE',
  price_complete TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否完整取价：1 是，0 否',
  generated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '价格生成时间',
  calc_batch_id VARCHAR(64) DEFAULT NULL COMMENT '生成批次ID',
  business_unit_type VARCHAR(32) DEFAULT NULL COMMENT '业务单元类型',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pkg_price_month (package_material_code, period_month),
  KEY idx_pkg_price_snapshot (snapshot_id),
  KEY idx_pkg_price_period_status (period_month, price_status),
  KEY idx_pkg_price_batch (calc_batch_id),
  KEY idx_pkg_price_bu (business_unit_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='包装组件月度价格主表';

CREATE TABLE IF NOT EXISTS lp_package_component_price_detail (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  price_id BIGINT NOT NULL COMMENT '包装价格主表ID',
  snapshot_detail_id BIGINT DEFAULT NULL COMMENT '对应结构快照明细ID',
  package_material_code VARCHAR(64) NOT NULL COMMENT '包装组件父料号',
  period_month VARCHAR(7) NOT NULL COMMENT '期间 YYYY-MM',
  line_no INT NOT NULL COMMENT '价格明细行号',
  child_material_code VARCHAR(64) NOT NULL COMMENT '子件料号',
  child_material_name VARCHAR(180) DEFAULT NULL COMMENT '子件名称',
  child_material_spec VARCHAR(255) DEFAULT NULL COMMENT '子件规格',
  qty_per_parent DECIMAL(20,8) DEFAULT NULL COMMENT '子件用量',
  price_type VARCHAR(32) DEFAULT NULL COMMENT '路由到的价格类型',
  source_price_type_text VARCHAR(64) DEFAULT NULL COMMENT '路由表原始价格类型文案',
  child_unit_price DECIMAL(20,8) DEFAULT NULL COMMENT '子件单价',
  child_amount DECIMAL(20,8) DEFAULT NULL COMMENT '子件金额：用量 * 单价',
  price_source VARCHAR(64) DEFAULT NULL COMMENT '命中的价格源类型',
  price_source_id BIGINT DEFAULT NULL COMMENT '命中的价格源记录ID，如可取到',
  price_status VARCHAR(32) NOT NULL DEFAULT 'PRICED' COMMENT '明细状态：PRICED/MISSING_ROUTE/MISSING_PRICE',
  missing_reason VARCHAR(500) DEFAULT NULL COMMENT '缺价原因',
  business_unit_type VARCHAR(32) DEFAULT NULL COMMENT '业务单元类型',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pkg_price_detail_line (price_id, line_no),
  KEY idx_pkg_price_detail_price (price_id),
  KEY idx_pkg_price_detail_snapshot (snapshot_detail_id),
  KEY idx_pkg_price_detail_pkg_period (package_material_code, period_month),
  KEY idx_pkg_price_detail_child (child_material_code),
  KEY idx_pkg_price_detail_status (price_status),
  KEY idx_pkg_price_detail_bu (business_unit_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='包装组件月度价格明细表';

CREATE TABLE IF NOT EXISTS lp_package_component_gap_item (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  period_month VARCHAR(7) NOT NULL COMMENT '期间 YYYY-MM',
  quote_no VARCHAR(64) DEFAULT NULL COMMENT '报价单号',
  oa_no VARCHAR(64) DEFAULT NULL COMMENT 'OA单号',
  top_product_code VARCHAR(64) DEFAULT NULL COMMENT '当前报价/BOM产品料号，仅追溯',
  package_material_code VARCHAR(64) NOT NULL COMMENT '包装组件父料号',
  package_material_name VARCHAR(180) DEFAULT NULL COMMENT '包装组件名称',
  line_no INT DEFAULT NULL COMMENT '子件行号，缺结构时可为空',
  child_material_code VARCHAR(64) DEFAULT NULL COMMENT '子件料号，缺结构时可为空',
  child_material_name VARCHAR(180) DEFAULT NULL COMMENT '子件名称',
  gap_type VARCHAR(32) NOT NULL COMMENT '异常类型：MISSING_STRUCTURE/MISSING_ROUTE/MISSING_PRICE',
  price_type VARCHAR(32) DEFAULT NULL COMMENT '已路由价格类型，取不到可为空',
  missing_material_code VARCHAR(64) DEFAULT NULL COMMENT '真正需要维护的料号',
  missing_reason VARCHAR(500) DEFAULT NULL COMMENT '异常说明',
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING_MAINTAIN' COMMENT '处理状态：PENDING_MAINTAIN/RESOLVED/IGNORED',
  oa_push_status VARCHAR(32) NOT NULL DEFAULT 'NOT_PUSHED' COMMENT 'OA推送状态预留：NOT_PUSHED/PUSHED/FAILED',
  business_unit_type VARCHAR(32) DEFAULT NULL COMMENT '业务单元类型',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_pkg_gap_period (period_month),
  KEY idx_pkg_gap_package (package_material_code),
  KEY idx_pkg_gap_child (child_material_code),
  KEY idx_pkg_gap_missing (missing_material_code),
  KEY idx_pkg_gap_status (status, oa_push_status),
  KEY idx_pkg_gap_type (gap_type),
  KEY idx_pkg_gap_quote_oa (quote_no, oa_no),
  KEY idx_pkg_gap_bu (business_unit_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='包装组件缺结构缺价记录表';
