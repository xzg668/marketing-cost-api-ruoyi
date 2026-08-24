-- 财务《BOM需要展示母件的规则》：特殊采购子件上卷改为正式料品档案分类判断。
-- 三个业务条件必须同时满足：形态属性=采购件、采购分类名称在28项白名单、主分类代码不在8项排除清单。
-- 本迁移只替换规则数据，不新增或修改业务表结构。

SET NAMES utf8mb4;

-- 旧的特殊采购上卷规则按品名匹配，统一停用并软删除，保留历史主键供已生成结算行追溯。
UPDATE lp_bom_settlement_rule
SET enabled = 0,
    deleted = 1,
    updated_by = 'V224',
    updated_at = NOW()
WHERE rule_category = 'SPECIAL_PURCHASE_ROLLUP'
  AND rule_code <> 'SPECIAL_PURCHASE_ROLLUP_FINANCE_CLASSIFICATION';

INSERT INTO lp_bom_settlement_rule (
  rule_code, rule_name, rule_category, settlement_action, settlement_row_type, sub_ref_type,
  match_condition_json, mark_subtree_cost_required, priority, enabled, business_unit_type,
  bom_purpose, effective_from, effective_to, remark, created_by, updated_by, deleted
) VALUES (
  'SPECIAL_PURCHASE_ROLLUP_FINANCE_CLASSIFICATION',
  '特殊采购分类上卷：财务分类规则',
  'SPECIAL_PURCHASE_ROLLUP',
  'ROLLUP_TO_PARENT',
  'SPECIAL_ROLLUP_PARENT',
  'SPECIAL_ROLLUP_CHILD',
  JSON_OBJECT('nodeConditions', JSON_ARRAY(
    JSON_OBJECT('field', 'shape_attr', 'op', 'EQ', 'value', '采购件'),
    JSON_OBJECT(
      'field', 'purchase_category',
      'op', 'IN',
      'values', JSON_ARRAY(
        '挤压铜棒', '不锈钢棒', '锻镦件', '软磁不锈钢棒', '铝棒', '铸造件',
        '不锈钢钢管', '无缝钢管', '紫铜直管', '焊接钢管', '金加工件', '不锈钢板带',
        '冲压拉伸件', '黄铜管', '其它管件', '钢丝', '碳钢钢棒', '注塑件',
        '其它钢材', '电镀类', '紫铜盘管', 'PEEK', '连铸铜棒', '粉末冶金件',
        '紫铜板带', '漆包线', '丝网', '铜包铝漆包线'
      )
    ),
    JSON_OBJECT('field', 'main_category_code', 'op', 'NOT_BLANK'),
    JSON_OBJECT(
      'field', 'main_category_code',
      'op', 'NOT_IN',
      'values', JSON_ARRAY(
        '121191304', '121181508', '151511373', '151521376',
        '121151306', '171721412', '121181301', '171751410'
      )
    )
  )),
  1,
  10,
  1,
  NULL,
  NULL,
  NULL,
  NULL,
  '末级采购子件同时满足财务28项采购分类白名单且主分类不在8项排除清单时，上卷到直接母件',
  'V224',
  'V224',
  0
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
  business_unit_type = VALUES(business_unit_type),
  bom_purpose = VALUES(bom_purpose),
  effective_from = VALUES(effective_from),
  effective_to = VALUES(effective_to),
  remark = VALUES(remark),
  updated_by = VALUES(updated_by),
  deleted = VALUES(deleted),
  updated_at = NOW();
