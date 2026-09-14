-- 业务修订条件D：原母件主分类排除全部作废，改为按子件主分类与子件名称筛选。
-- 前四项条件和上卷动作保持不变；专用零部件主分类正式编码为121191304。
SET NAMES utf8mb4;

SET @rollup_child_exclusions = JSON_ARRAY(
  JSON_OBJECT(
    'nodeConditions', JSON_ARRAY(
      JSON_OBJECT('field', 'main_category_code', 'op', 'EQ', 'value', '121191304'),
      JSON_OBJECT('field', 'material_name', 'op', 'NOT_LIKE', 'value', '坯'),
      JSON_OBJECT('field', 'material_name', 'op', 'NOT_LIKE', 'value', '半成品'),
      JSON_OBJECT('field', 'material_name', 'op', 'NOT_LIKE', 'value', '加强板'),
      JSON_OBJECT('field', 'material_name', 'op', 'NOT_LIKE', 'value', '落料板'),
      JSON_OBJECT('field', 'material_name', 'op', 'NOT_LIKE', 'value', '内齿圈')
    )
  )
);

-- 只在条件实际变化时使工作区失效，重复执行不会再次打断已按新规则生成的草稿。
SET @rollup_child_rule_changed = (
  SELECT COUNT(*)
  FROM lp_bom_settlement_rule
  WHERE rule_code = 'SPECIAL_PURCHASE_ROLLUP_FINANCE_CLASSIFICATION'
    AND NOT (JSON_EXTRACT(match_condition_json, '$.excludeGroups')
      <=> CAST(@rollup_child_exclusions AS JSON))
);

UPDATE lp_bom_settlement_rule
SET match_condition_json = JSON_SET(
      match_condition_json, '$.excludeGroups', CAST(@rollup_child_exclusions AS JSON)),
    remark = '末级采购子件满足28项采购分类，直接母件为制造件且存在有效副产品；子件非专用零部件通过，专用零部件名称须包含坯、半成品、加强板、落料板、内齿圈之一，上卷展示直接母件',
    updated_by = 'V259',
    updated_at = NOW()
WHERE rule_code = 'SPECIAL_PURCHASE_ROLLUP_FINANCE_CLASSIFICATION'
  AND @rollup_child_rule_changed > 0;

-- 旧结算行需按新规则重建；保留历史成本版本与冻结快照。
UPDATE lp_quote_costing_workspace
SET workspace_status = 'STALE',
    current_step = 'QUOTE_BOM',
    stale_reason_code = 'BOM_RULE_CHANGED',
    last_error_step = NULL,
    last_error_code = NULL,
    last_error_message = NULL,
    lock_version = lock_version + 1,
    updated_at = NOW()
WHERE current_bom_build_batch_id IS NOT NULL
  AND @rollup_child_rule_changed > 0;
