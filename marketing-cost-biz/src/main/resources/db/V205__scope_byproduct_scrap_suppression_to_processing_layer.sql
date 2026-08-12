SET NAMES utf8mb4;

UPDATE lp_bom_byproduct_cost_rule
SET
  rule_name = '同加工层未命中废料映射时输出副产品',
  remark = '采购件废料映射只抑制最近加工父件；更上级制造件按各自 U9 主制造副产品逐级独立输出负数结算行'
WHERE rule_code = 'BYPRODUCT_EXTRA_WHEN_NO_SCRAP_REF';
