-- =============================================================================
-- V89: 供应商供货比例主数据
-- -----------------------------------------------------------------------------
-- 说明：
--   1. lp_supplier_supply_ratio 是供应关系主数据，不是价格源表。
--   2. Excel 当前只是一种来源，后续 SRM 同步也写入同一张业务表。
--   3. 唯一键按用户确认口径去重：物料代码 + 物料名称 + 供应商 + 型号。
-- =============================================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS lp_supplier_supply_ratio (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  business_unit_type VARCHAR(32) NOT NULL DEFAULT 'COMMERCIAL' COMMENT '业务单元类型',
  material_code VARCHAR(64) NOT NULL COMMENT '物料代码',
  material_name VARCHAR(180) NOT NULL COMMENT '物料名称',
  spec_model VARCHAR(255) NOT NULL COMMENT '型号/规格型号',
  unit VARCHAR(32) DEFAULT NULL COMMENT '单位',
  material_shape VARCHAR(64) DEFAULT NULL COMMENT '物料形态属性',
  supplier_name VARCHAR(180) NOT NULL COMMENT '供应商名称',
  supplier_code VARCHAR(64) DEFAULT NULL COMMENT '供应商代码，当前 Excel 可为空，后续 SRM 可补充',
  supply_ratio DECIMAL(18,6) NOT NULL DEFAULT 0.000000 COMMENT '供货比例，取价时优先选择比例最大的供应商',
  effective_from DATE DEFAULT NULL COMMENT '生效日期，Excel 导入阶段可为空',
  effective_to DATE DEFAULT NULL COMMENT '失效日期',
  source_type VARCHAR(32) NOT NULL DEFAULT 'EXCEL' COMMENT '来源类型：EXCEL/SRM/MANUAL',
  source_batch_no VARCHAR(64) DEFAULT NULL COMMENT '来源批次号',
  import_file_name VARCHAR(255) DEFAULT NULL COMMENT '导入文件名',
  imported_by VARCHAR(64) DEFAULT NULL COMMENT '导入人/同步人',
  imported_at DATETIME DEFAULT NULL COMMENT '导入/同步时间',
  created_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=有效，1=删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_supplier_ratio_biz (
    business_unit_type,
    material_code,
    material_name,
    supplier_name,
    spec_model,
    deleted
  ),
  KEY idx_supplier_ratio_material (
    business_unit_type,
    material_code,
    material_name,
    spec_model,
    supply_ratio
  ),
  KEY idx_supplier_ratio_supplier (business_unit_type, supplier_name, deleted),
  KEY idx_supplier_ratio_source (source_type, source_batch_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='供应商供货比例主数据：用于多供应商价格命中时选择主供供应商';
