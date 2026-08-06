-- 财务《明细辅料筛选》：辅料排除改为正式料品档案编码判断。
-- 物料属性来自 lp_material_master；本迁移只复用现有结算规则表，不新增业务表。

UPDATE lp_bom_settlement_rule
SET enabled = 0,
    remark = '已由主分类编码规则替代（V196）',
    updated_at = NOW()
WHERE rule_code IN (
  'AUXILIARY_EXCLUDE_GREASE',
  'AUXILIARY_EXCLUDE_OIL',
  'AUXILIARY_EXCLUDE_ADHESIVE'
);

INSERT INTO lp_bom_settlement_rule (
  rule_code, rule_name, rule_category, settlement_action, settlement_row_type, sub_ref_type,
  match_condition_json, mark_subtree_cost_required, priority, enabled, remark
) VALUES (
  'AUXILIARY_EXCLUDE_FINANCE_MAIN_CATEGORIES',
  '辅料排除：财务主分类清单',
  'AUXILIARY_EXCLUDE',
  'EXCLUDE',
  'EXCLUDED',
  NULL,
  JSON_OBJECT('nodeConditions', JSON_ARRAY(
    JSON_OBJECT(
      'field', 'main_category_code',
      'op', 'IN',
      'values', JSON_ARRAY(
        '181841442', '181851445', '181811435', '181851454', '181841443',
        '181861452', '171721414', '181861453', '181831498', '171741425'
      )
    )
  )),
  0,
  40,
  1,
  '命中财务排除主分类编码时不输出 BOM 结算行；属性取 lp_material_master'
), (
  'AUXILIARY_EXCLUDE_PLASTIC_EXCEPT_KEEP',
  '辅料排除：塑料（采购分类例外保留）',
  'AUXILIARY_EXCLUDE',
  'EXCLUDE',
  'EXCLUDED',
  NULL,
  JSON_OBJECT('nodeConditions', JSON_ARRAY(
    JSON_OBJECT('field', 'main_category_code', 'op', 'EQ', 'value', '171721412'),
    JSON_OBJECT(
      'field', 'purchase_category',
      'op', 'NOT_IN',
      'values', JSON_ARRAY('PEEK', 'PVC套管', '其它高分子材料', '其它橡塑制品', '热缩管', '注塑件')
    )
  )),
  0,
  41,
  1,
  '塑料默认排除；财务指定的六类采购分类保留'
), (
  'AUXILIARY_EXCLUDE_ADHESIVE_AUX_EXCEPT_PACKAGE',
  '辅料排除：粘胶辅料（其它包装材料除外）',
  'AUXILIARY_EXCLUDE',
  'EXCLUDE',
  'EXCLUDED',
  NULL,
  JSON_OBJECT('nodeConditions', JSON_ARRAY(
    JSON_OBJECT('field', 'main_category_code', 'op', 'EQ', 'value', '181841444'),
    JSON_OBJECT('field', 'purchase_category', 'op', 'NE', 'value', '其它包装材料')
  )),
  0,
  42,
  1,
  '粘胶辅料默认排除；采购分类为其它包装材料时保留'
)
ON DUPLICATE KEY UPDATE
  rule_name = VALUES(rule_name),
  rule_category = VALUES(rule_category),
  settlement_action = VALUES(settlement_action),
  settlement_row_type = VALUES(settlement_row_type),
  sub_ref_type = VALUES(sub_ref_type),
  match_condition_json = VALUES(match_condition_json),
  mark_subtree_cost_required = VALUES(mark_subtree_cost_required),
  priority = VALUES(priority),
  enabled = VALUES(enabled),
  remark = VALUES(remark),
  updated_at = NOW();
