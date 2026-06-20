-- =============================================================================
-- V174: 成本核算底稿快照
-- -----------------------------------------------------------------------------
-- 说明：
--   1. 底稿跟随成本版本生成，查询时只读快照，不回查最新价格源。
--   2. trace_type + trace_key 标识成本表上的可追溯金额。
--   3. 来源、公式、变量、步骤和下级明细使用 JSON 保存，避免不同来源结构摊平成大量空字段。
-- =============================================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS lp_cost_run_trace_snapshot (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  cost_run_version_id BIGINT NOT NULL COMMENT '成本版本ID，关联 lp_quote_cost_run_version.id',
  cost_run_no VARCHAR(64) NOT NULL COMMENT '成本核算批次号',
  version_no VARCHAR(64) DEFAULT NULL COMMENT '确认版本号；试算版本可为空',
  oa_no VARCHAR(64) NOT NULL COMMENT 'OA单号',
  oa_form_item_id BIGINT NOT NULL COMMENT 'OA产品明细行ID',
  product_code VARCHAR(64) NOT NULL COMMENT '顶层产品料号',
  pricing_month VARCHAR(7) NOT NULL COMMENT '计价月份 YYYY-MM',
  trace_type VARCHAR(32) NOT NULL COMMENT '底稿类型：PART_PRICE/COST_ITEM/TOTAL',
  trace_key VARCHAR(128) NOT NULL COMMENT '版本内唯一业务键，如 PART:{partItemId}、COST:{costCode}、TOTAL',
  part_item_id BIGINT DEFAULT NULL COMMENT '关联 lp_cost_run_part_item.id，费用项为空',
  cost_item_id BIGINT DEFAULT NULL COMMENT '关联 lp_cost_run_cost_item.id，部品为空',
  bom_row_id BIGINT DEFAULT NULL COMMENT '报价BOM行ID，费用项为空',
  price_prepare_item_id BIGINT DEFAULT NULL COMMENT '最终价格准备明细ID',
  material_code VARCHAR(64) DEFAULT NULL COMMENT '部品料号',
  material_name VARCHAR(180) DEFAULT NULL COMMENT '部品名称',
  cost_code VARCHAR(64) DEFAULT NULL COMMENT '费用编码',
  cost_name VARCHAR(180) DEFAULT NULL COMMENT '费用名称',
  source_type VARCHAR(64) DEFAULT NULL COMMENT '来源类型：FIXED_PRICE/LINKED_PRICE/MAKE_PART/PACKAGE_COMPONENT/CMS/RATE_CONFIG/ROLLUP',
  source_batch_no VARCHAR(128) DEFAULT NULL COMMENT '来源批次号',
  source_ref_id BIGINT DEFAULT NULL COMMENT '来源记录ID',
  unit_price DECIMAL(20,8) DEFAULT NULL COMMENT '单价',
  quantity DECIMAL(20,8) DEFAULT NULL COMMENT '数量或BOM用量',
  base_amount DECIMAL(20,8) DEFAULT NULL COMMENT '费用基数',
  rate DECIMAL(20,8) DEFAULT NULL COMMENT '费率',
  amount DECIMAL(20,8) DEFAULT NULL COMMENT '本项结果金额',
  summary VARCHAR(512) DEFAULT NULL COMMENT '业务摘要',
  source_snapshot_json JSON DEFAULT NULL COMMENT '来源快照 JSON',
  formula_snapshot_json JSON DEFAULT NULL COMMENT '公式快照 JSON',
  variables_json JSON DEFAULT NULL COMMENT '变量取值 JSON',
  steps_json JSON DEFAULT NULL COMMENT '计算步骤 JSON',
  children_json JSON DEFAULT NULL COMMENT '自制件/包装组件等下级明细 JSON',
  business_unit_type VARCHAR(32) DEFAULT NULL COMMENT '业务单元类型',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_cost_trace_key (cost_run_no, trace_type, trace_key),
  KEY idx_cost_trace_version (cost_run_version_id),
  KEY idx_cost_trace_run (cost_run_no),
  KEY idx_cost_trace_part (cost_run_no, material_code),
  KEY idx_cost_trace_cost (cost_run_no, cost_code),
  KEY idx_cost_trace_prepare_item (price_prepare_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='成本核算底稿快照表';
