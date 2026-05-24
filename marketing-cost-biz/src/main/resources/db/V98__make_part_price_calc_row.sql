-- =============================================================================
-- V98: 制造件价格生成明细表
-- -----------------------------------------------------------------------------
-- 说明：
--   1. 本表是制造件价格生成快照，一行对应 parent + child + scrap 的一条核算明细。
--   2. 重量字段统一为 g；凡是和元/kg 单价相乘，业务代码必须先除以 1000 转 kg。
--   3. raw_unit_price：原材料加工为元/kg，毛坯加工为元/件。
--   4. scrap_unit_price：元/kg。
-- =============================================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS lp_make_part_price_calc_row (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  calc_batch_id VARCHAR(64) NOT NULL COMMENT '生成批次ID',
  oa_no VARCHAR(64) DEFAULT NULL COMMENT 'OA单号；全量生成时可为空',
  business_unit_type VARCHAR(32) DEFAULT NULL COMMENT '业务单元类型',
  parent_material_no VARCHAR(64) NOT NULL COMMENT '制造件料号',
  parent_material_name VARCHAR(180) DEFAULT NULL COMMENT '制造件名称',
  drawing_no VARCHAR(255) DEFAULT NULL COMMENT '图号/规格',
  item_process_type VARCHAR(32) NOT NULL COMMENT '料件类型：毛坯加工/原材料加工',
  child_material_no VARCHAR(64) NOT NULL COMMENT '原材料/毛坯料号',
  child_material_name VARCHAR(180) DEFAULT NULL COMMENT '原材料/毛坯名称',
  child_material_spec VARCHAR(255) DEFAULT NULL COMMENT '原材料/毛坯规格',
  stock_unit VARCHAR(32) DEFAULT NULL COMMENT 'U9子项库存主单位；只有“只”按毛坯加工处理',
  qty_per_parent DECIMAL(20,8) DEFAULT NULL COMMENT 'U9子项用量；原材料加工时按 kg 转 g 作为毛重',
  gross_weight_g DECIMAL(20,6) DEFAULT NULL COMMENT '毛重，单位 g',
  net_weight_g DECIMAL(20,6) DEFAULT NULL COMMENT '净重，单位 g',
  raw_price_type VARCHAR(32) DEFAULT NULL COMMENT '原材料价格类型，来自 lp_material_price_type',
  raw_unit_price DECIMAL(20,8) DEFAULT NULL COMMENT '采购单价；原材料加工为元/kg，毛坯加工为元/件',
  scrap_code VARCHAR(64) DEFAULT NULL COMMENT '回收废料料号',
  scrap_name VARCHAR(180) DEFAULT NULL COMMENT '回收废料名称',
  scrap_price_type VARCHAR(32) DEFAULT NULL COMMENT '回收废料价格类型，来自 lp_material_price_type',
  scrap_unit_price DECIMAL(20,8) DEFAULT NULL COMMENT '回收单价，元/kg',
  outsource_fee DECIMAL(20,6) NOT NULL DEFAULT 0.000000 COMMENT '委外加工费；第一版不参与制造件公式',
  cost_price DECIMAL(20,8) DEFAULT NULL COMMENT '当前 child + scrap 明细行成本价格',
  parent_total_cost_price DECIMAL(20,8) DEFAULT NULL COMMENT '同一制造件在当前批次下的汇总成本价格',
  status VARCHAR(32) NOT NULL DEFAULT 'OK' COMMENT '状态：OK/MISSING_BOM_CHILD/MISSING_STOCK_UNIT/MISSING_ROUTE/MISSING_PRICE/MISSING_WEIGHT/MISSING_SCRAP/MULTI_CHILD/MULTI_SCRAP',
  remark VARCHAR(1024) DEFAULT NULL COMMENT '计算追溯和异常说明',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_make_calc_batch (calc_batch_id),
  KEY idx_make_calc_parent (parent_material_no),
  KEY idx_make_calc_batch_parent (calc_batch_id, parent_material_no),
  KEY idx_make_calc_child (child_material_no),
  KEY idx_make_calc_scrap (scrap_code),
  KEY idx_make_calc_oa_bu (oa_no, business_unit_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='制造件价格生成明细表';
