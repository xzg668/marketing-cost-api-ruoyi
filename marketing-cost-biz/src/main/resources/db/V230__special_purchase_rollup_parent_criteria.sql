-- 财务《BOM需要展示母件的规则-0824》：特殊采购子件上卷增加母件制造形态、
-- 有效副产品产出和按母件主分类+子件品名组合排除条件。
-- 本迁移只更新规则数据和工作区状态，不新增表或字段。

SET NAMES utf8mb4;

UPDATE lp_bom_settlement_rule
SET rule_name = '特殊采购分类上卷：母件副产品规则',
    match_condition_json = JSON_OBJECT(
      'nodeConditions', JSON_ARRAY(
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
        )
      ),
      'parentConditions', JSON_ARRAY(
        JSON_OBJECT('field', 'shape_attr', 'op', 'EQ', 'value', '制造件'),
        JSON_OBJECT('field', 'has_byproduct', 'op', 'EQ', 'value', 'true')
      ),
      'excludeGroups', JSON_ARRAY(
        JSON_OBJECT(
          'parentConditions', JSON_ARRAY(
            JSON_OBJECT(
              'field', 'main_category_code',
              'op', 'IN',
              'values', JSON_ARRAY('101001018', '111001018', '101001007', '111001007')
            )
          )
        ),
        JSON_OBJECT(
          'parentConditions', JSON_ARRAY(
            JSON_OBJECT('field', 'main_category_code', 'op', 'EQ', 'value', '121151306')
          ),
          'nodeConditions', JSON_ARRAY(
            JSON_OBJECT('field', 'material_name', 'op', 'LIKE', 'value', '分磁环')
          )
        ),
        JSON_OBJECT(
          'parentConditions', JSON_ARRAY(
            JSON_OBJECT('field', 'main_category_code', 'op', 'EQ', 'value', '121191304')
          ),
          'nodeConditions', JSON_ARRAY(
            JSON_OBJECT('field', 'material_name', 'op', 'NOT_LIKE', 'value', '毛坯'),
            JSON_OBJECT('field', 'material_name', 'op', 'NOT_LIKE', 'value', '半成品')
          )
        )
      )
    ),
    remark = '末级采购子件满足财务28项采购分类白名单，直接母件为制造件且存在有效副产品产出，并通过母件主分类与子件品名排除校验时，上卷到直接母件',
    enabled = 1,
    deleted = 0,
    updated_by = 'V230',
    updated_at = NOW()
WHERE rule_code = 'SPECIAL_PURCHASE_ROLLUP_FINANCE_CLASSIFICATION';

-- 规则变化后，已有核算工作区必须回到报价BOM步骤重算，不能继续使用旧规则生成的结算行。
UPDATE lp_quote_costing_workspace
SET workspace_status = 'STALE',
    current_step = 'QUOTE_BOM',
    stale_reason_code = 'BOM_RULE_CHANGED',
    last_error_step = NULL,
    last_error_code = NULL,
    last_error_message = NULL,
    lock_version = lock_version + 1,
    updated_at = NOW()
WHERE current_bom_build_batch_id IS NOT NULL;
