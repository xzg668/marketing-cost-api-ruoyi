-- 烧结基座按其自身料号取价，作为委外加工费；其下材料继续沿原规则计价。
-- 名称识别适用于所有料号，毛坯仅排除此附加规则，不从 BOM/材料计价中排除。
-- 仅新增一条配置，不修改既有形态策略、供货比率、月价或历史报价成本。
INSERT INTO lp_bom_settlement_rule (
  rule_code, rule_name, rule_category, settlement_action, settlement_row_type,
  match_condition_json, mark_subtree_cost_required, priority, enabled, remark,
  created_by, updated_by, deleted
)
SELECT 'SINTERED_BASE_PROCESS_FEE', '烧结基座委外加工费', 'OUTSOURCED_PROCESS_FEE',
  'ADD_PROCESS_FEE', 'OUTSOURCED_PROCESS_FEE',
  JSON_OBJECT('nodeConditions', JSON_ARRAY(
    JSON_OBJECT('field', 'material_name', 'op', 'LIKE', 'value', '烧结基座'),
    JSON_OBJECT('field', 'material_name', 'op', 'NOT_LIKE', 'value', '毛坯')
  )), 0, 10, 1,
  '烧结基座自身价格计作委外加工费，保留下层材料费用；烧结基座毛坯不适用本附加规则。',
  'V367', 'V367', 0
WHERE NOT EXISTS (
  SELECT 1 FROM lp_bom_settlement_rule WHERE rule_code = 'SINTERED_BASE_PROCESS_FEE'
);
