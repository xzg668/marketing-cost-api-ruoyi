SET NAMES utf8mb4;

UPDATE lp_bom_settlement_rule
SET
  rule_name = '特殊子项品名上卷：丝网',
  match_condition_json = JSON_OBJECT(
    'nodeConditions',
    JSON_ARRAY(JSON_OBJECT('field', 'material_name', 'op', 'LIKE', 'value', '丝网'))
  ),
  remark = '末级采购件子项品名包含丝网时，输出直接父件作为结算行'
WHERE rule_code = 'SPECIAL_PURCHASE_ROLLUP_MESH';

UPDATE lp_bom_settlement_rule
SET
  rule_name = '特殊子项品名上卷：不锈钢板带',
  match_condition_json = JSON_OBJECT(
    'nodeConditions',
    JSON_ARRAY(JSON_OBJECT('field', 'material_name', 'op', 'LIKE', 'value', '不锈钢板带'))
  ),
  remark = '末级采购件子项品名包含不锈钢板带时，输出直接父件作为结算行'
WHERE rule_code = 'SPECIAL_PURCHASE_ROLLUP_STAINLESS_STRIP';

UPDATE lp_bom_settlement_rule
SET
  rule_name = '特殊子项品名上卷：软磁不锈钢棒',
  match_condition_json = JSON_OBJECT(
    'nodeConditions',
    JSON_ARRAY(JSON_OBJECT('field', 'material_name', 'op', 'LIKE', 'value', '软磁不锈钢棒'))
  ),
  remark = '末级采购件子项品名包含软磁不锈钢棒时，输出直接父件作为结算行'
WHERE rule_code = 'SPECIAL_PURCHASE_ROLLUP_SOFT_MAGNETIC_STAINLESS_BAR';

UPDATE lp_bom_settlement_rule
SET
  rule_name = '特殊子项品名上卷：紫铜直管',
  match_condition_json = JSON_OBJECT(
    'nodeConditions',
    JSON_ARRAY(JSON_OBJECT('field', 'material_name', 'op', 'LIKE', 'value', '紫铜直管'))
  ),
  remark = '末级采购件子项品名包含紫铜直管时，输出直接父件作为结算行'
WHERE rule_code = 'SPECIAL_PURCHASE_ROLLUP_PURPLE_COPPER_STRAIGHT_TUBE';

UPDATE lp_bom_settlement_rule
SET
  rule_name = '特殊子项品名上卷：拉制铜管',
  match_condition_json = JSON_OBJECT(
    'nodeConditions',
    JSON_ARRAY(JSON_OBJECT('field', 'material_name', 'op', 'LIKE', 'value', '拉制铜管'))
  ),
  remark = '末级采购件子项品名包含拉制铜管时，输出直接父件作为结算行'
WHERE rule_code = 'SPECIAL_PURCHASE_ROLLUP_DRAWN_COPPER_TUBE';
