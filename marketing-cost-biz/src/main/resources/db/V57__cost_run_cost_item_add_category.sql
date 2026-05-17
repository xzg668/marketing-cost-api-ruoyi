-- T24: lp_cost_run_cost_item 加 category 字段，区分两类语义：
--   EXPENSE    = 传统费用项（14 个 cost_code，参与 totalAmount 累加）
--   BOM_BUCKET = 见机表原材料汇总视图（焊料 / 包装等，仅展示，不参与累加）
--
-- 设计文档：docs/cost-bucket-aggregation-20260501-design.md
-- 旧数据 default='EXPENSE' 自动归类，行为零变化。

ALTER TABLE lp_cost_run_cost_item
  ADD COLUMN category VARCHAR(20) NOT NULL DEFAULT 'EXPENSE'
    COMMENT 'EXPENSE=传统费用项(参与累加) / BOM_BUCKET=见机表汇总(仅展示)'
    AFTER product_code;

-- 联合索引：listStoredByOaNo 默认 WHERE oa_no=? AND product_code=? AND category='EXPENSE' 走该索引
ALTER TABLE lp_cost_run_cost_item
  ADD INDEX idx_oa_product_category (oa_no, product_code, category);
