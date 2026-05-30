SET NAMES utf8mb4;

UPDATE lp_bom_byproduct_cost_rule
SET
  enabled = 0,
  remark = 'BSR-06 验收前默认关闭；当前制造件存在 U9 主制造副产品，且下层原材料未命中 lp_material_scrap_ref 时，副产品额外输出为结算行'
WHERE rule_code = 'BYPRODUCT_EXTRA_WHEN_NO_SCRAP_REF';
