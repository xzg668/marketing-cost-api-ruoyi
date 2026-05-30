-- =============================================================================
-- V146: BOM 结算规则新模型
-- -----------------------------------------------------------------------------
-- 本脚本只落地新规则数据模型，不删除旧 bom_stop_drill_rule。
--
-- lp_bom_settlement_rule：
--   负责 BOM 树节点上的结算粒度规则，例如特殊子项品名上卷父件、辅料排除。
--   这类规则决定“当前 BOM 节点是否生成结算行、是否上卷、是否排除”。
--
-- lp_bom_byproduct_cost_rule：
--   负责副产品附加结算行规则。副产品不是 BOM 树上的停止下钻/上卷节点，
--   而是基于 U9 副产品档案与 lp_material_scrap_ref 判断后额外补一行结算。
--
-- lp_bom_costing_row / lp_bom_costing_row_sub_ref：
--   继续作为结算结果表；本次只补充新规则追溯字段，保留旧 matched_drill_rule_id。
-- =============================================================================

SET NAMES utf8mb4;

DELIMITER $$

DROP PROCEDURE IF EXISTS v146_add_column_if_not_exists $$
CREATE PROCEDURE v146_add_column_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_column_name VARCHAR(64),
  IN p_column_def TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
      FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND COLUMN_NAME = p_column_name
  ) THEN
    SET @sql = CONCAT('ALTER TABLE ', p_table_name, ' ADD COLUMN ', p_column_name, ' ', p_column_def);
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

DROP PROCEDURE IF EXISTS v146_add_index_if_not_exists $$
CREATE PROCEDURE v146_add_index_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_index_name VARCHAR(64),
  IN p_index_def TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
      FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND INDEX_NAME = p_index_name
  ) THEN
    SET @sql = CONCAT('ALTER TABLE ', p_table_name, ' ADD ', p_index_def);
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

DELIMITER ;

CREATE TABLE IF NOT EXISTS lp_bom_settlement_rule (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  rule_code VARCHAR(64) NOT NULL COMMENT '规则编码，业务稳定唯一键',
  rule_name VARCHAR(128) NOT NULL COMMENT '规则名称',
  rule_category VARCHAR(32) NOT NULL COMMENT '树节点规则分类：SPECIAL_PURCHASE_ROLLUP/AUXILIARY_EXCLUDE/PACKAGE_STOP/OUTSOURCED_PROCESS_FEE',
  settlement_action VARCHAR(32) NOT NULL COMMENT '命中动作：ROLLUP_TO_PARENT/EXCLUDE/STOP_AS_PACKAGE/ADD_PROCESS_FEE',
  settlement_row_type VARCHAR(32) NOT NULL COMMENT '生成的结算行类型：SPECIAL_ROLLUP_PARENT/PACKAGE_PARENT/OUTSOURCED_PROCESS_FEE 等',
  sub_ref_type VARCHAR(32) DEFAULT NULL COMMENT '写入 lp_bom_costing_row_sub_ref 时的来源类型；非上卷规则可为空',
  match_condition_json JSON NOT NULL COMMENT '树节点规则匹配条件 JSON；只描述 BOM 节点字段，不描述副产品附加逻辑',
  mark_subtree_cost_required TINYINT(1) NOT NULL DEFAULT 0 COMMENT '命中后父结算行是否需要下游子树取价',
  priority INT NOT NULL DEFAULT 100 COMMENT '优先级，数字越小越先命中',
  enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用：1启用/0停用',
  business_unit_type VARCHAR(16) DEFAULT NULL COMMENT '业务单元：NULL=全局，COMMERCIAL/HOUSEHOLD 限定',
  bom_purpose VARCHAR(32) DEFAULT NULL COMMENT 'BOM 生产目的：NULL=不限定，主制造/普机等',
  effective_from DATE DEFAULT NULL COMMENT '规则生效日期',
  effective_to DATE DEFAULT NULL COMMENT '规则失效日期',
  remark VARCHAR(255) DEFAULT NULL COMMENT '规则说明',
  created_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted TINYINT(1) NOT NULL DEFAULT 0 COMMENT '软删标记：0正常/1删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_bom_settlement_rule_code (rule_code),
  KEY idx_bom_settlement_rule_lookup (enabled, deleted, business_unit_type, bom_purpose, priority),
  KEY idx_bom_settlement_rule_category (rule_category, settlement_action)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='BOM 树节点结算规则：特殊子项品名上卷、辅料排除、包装截断等';

CREATE TABLE IF NOT EXISTS lp_bom_byproduct_cost_rule (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  rule_code VARCHAR(64) NOT NULL COMMENT '规则编码，业务稳定唯一键',
  rule_name VARCHAR(128) NOT NULL COMMENT '规则名称',
  rule_category VARCHAR(32) NOT NULL DEFAULT 'BYPRODUCT_EXTRA' COMMENT '副产品附加规则分类',
  add_condition_type VARCHAR(64) NOT NULL COMMENT '附加条件：NO_SCRAP_REF_MATCH 表示未命中 lp_material_scrap_ref 时输出',
  settlement_row_type VARCHAR(32) NOT NULL DEFAULT 'BYPRODUCT_EXTRA' COMMENT '生成的结算行类型',
  match_condition_json JSON DEFAULT NULL COMMENT '副产品附加规则匹配条件 JSON；区别于 BOM 树节点规则',
  priority INT NOT NULL DEFAULT 100 COMMENT '优先级，数字越小越先命中',
  enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用：1启用/0停用',
  business_unit_type VARCHAR(16) DEFAULT NULL COMMENT '业务单元：NULL=全局，COMMERCIAL/HOUSEHOLD 限定',
  bom_purpose VARCHAR(32) DEFAULT NULL COMMENT 'BOM 生产目的：NULL=不限定，主制造/普机等',
  effective_from DATE DEFAULT NULL COMMENT '规则生效日期',
  effective_to DATE DEFAULT NULL COMMENT '规则失效日期',
  remark VARCHAR(255) DEFAULT NULL COMMENT '规则说明',
  created_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted TINYINT(1) NOT NULL DEFAULT 0 COMMENT '软删标记：0正常/1删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_bom_byproduct_cost_rule_code (rule_code),
  KEY idx_bom_byproduct_cost_rule_lookup (enabled, deleted, business_unit_type, bom_purpose, priority)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='BOM 副产品附加结算行规则：基于 U9 副产品档案和废料映射判断是否额外输出';

CALL v146_add_column_if_not_exists(
  'lp_bom_costing_row',
  'matched_settlement_rule_id',
  'BIGINT DEFAULT NULL COMMENT ''命中的新 BOM 树节点结算规则 id；旧 matched_drill_rule_id 在 BSR-09 前保留'' AFTER matched_drill_rule_id'
);

CALL v146_add_column_if_not_exists(
  'lp_bom_costing_row',
  'settlement_row_type',
  'VARCHAR(32) NOT NULL DEFAULT ''DEFAULT_LEAF'' COMMENT ''结算行类型：DEFAULT_LEAF/PACKAGE_PARENT/SPECIAL_ROLLUP_PARENT/OUTSOURCED_PROCESS_FEE/BYPRODUCT_EXTRA'' AFTER matched_settlement_rule_id'
);

CALL v146_add_index_if_not_exists(
  'lp_bom_costing_row',
  'idx_bom_costing_settlement_rule',
  'INDEX idx_bom_costing_settlement_rule (matched_settlement_rule_id, settlement_row_type)'
);

CALL v146_add_column_if_not_exists(
  'lp_bom_costing_row_sub_ref',
  'ref_type',
  'VARCHAR(32) NOT NULL DEFAULT ''SPECIAL_ROLLUP_CHILD'' COMMENT ''子件引用类型：SPECIAL_ROLLUP_CHILD 等，用于区分上卷来源'' AFTER costing_row_id'
);

CALL v146_add_column_if_not_exists(
  'lp_bom_costing_row_sub_ref',
  'matched_settlement_rule_id',
  'BIGINT DEFAULT NULL COMMENT ''产生该子件引用的新 BOM 树节点结算规则 id'' AFTER ref_type'
);

CALL v146_add_index_if_not_exists(
  'lp_bom_costing_row_sub_ref',
  'idx_bom_sub_ref_type_rule',
  'INDEX idx_bom_sub_ref_type_rule (ref_type, matched_settlement_rule_id)'
);

INSERT INTO lp_bom_settlement_rule (
  rule_code, rule_name, rule_category, settlement_action, settlement_row_type, sub_ref_type,
  match_condition_json, mark_subtree_cost_required, priority, enabled, remark
)
SELECT
  'SPECIAL_PURCHASE_ROLLUP_MESH',
  '特殊子项品名上卷：丝网',
  'SPECIAL_PURCHASE_ROLLUP',
  'ROLLUP_TO_PARENT',
  'SPECIAL_ROLLUP_PARENT',
  'SPECIAL_ROLLUP_CHILD',
  JSON_OBJECT('nodeConditions', JSON_ARRAY(JSON_OBJECT('field', 'material_name', 'op', 'LIKE', 'value', '丝网'))),
  1,
  10,
  1,
  '末级采购件子项品名包含丝网时，输出直接父件作为结算行'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM lp_bom_settlement_rule WHERE rule_code = 'SPECIAL_PURCHASE_ROLLUP_MESH');

INSERT INTO lp_bom_settlement_rule (
  rule_code, rule_name, rule_category, settlement_action, settlement_row_type, sub_ref_type,
  match_condition_json, mark_subtree_cost_required, priority, enabled, remark
)
SELECT
  'SPECIAL_PURCHASE_ROLLUP_STAINLESS_STRIP',
  '特殊子项品名上卷：不锈钢板带',
  'SPECIAL_PURCHASE_ROLLUP',
  'ROLLUP_TO_PARENT',
  'SPECIAL_ROLLUP_PARENT',
  'SPECIAL_ROLLUP_CHILD',
  JSON_OBJECT('nodeConditions', JSON_ARRAY(JSON_OBJECT('field', 'material_name', 'op', 'LIKE', 'value', '不锈钢板带'))),
  1,
  11,
  1,
  '末级采购件子项品名包含不锈钢板带时，输出直接父件作为结算行'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM lp_bom_settlement_rule WHERE rule_code = 'SPECIAL_PURCHASE_ROLLUP_STAINLESS_STRIP');

INSERT INTO lp_bom_settlement_rule (
  rule_code, rule_name, rule_category, settlement_action, settlement_row_type, sub_ref_type,
  match_condition_json, mark_subtree_cost_required, priority, enabled, remark
)
SELECT
  'SPECIAL_PURCHASE_ROLLUP_SOFT_MAGNETIC_STAINLESS_BAR',
  '特殊子项品名上卷：软磁不锈钢棒',
  'SPECIAL_PURCHASE_ROLLUP',
  'ROLLUP_TO_PARENT',
  'SPECIAL_ROLLUP_PARENT',
  'SPECIAL_ROLLUP_CHILD',
  JSON_OBJECT('nodeConditions', JSON_ARRAY(JSON_OBJECT('field', 'material_name', 'op', 'LIKE', 'value', '软磁不锈钢棒'))),
  1,
  12,
  1,
  '末级采购件子项品名包含软磁不锈钢棒时，输出直接父件作为结算行'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM lp_bom_settlement_rule WHERE rule_code = 'SPECIAL_PURCHASE_ROLLUP_SOFT_MAGNETIC_STAINLESS_BAR');

INSERT INTO lp_bom_settlement_rule (
  rule_code, rule_name, rule_category, settlement_action, settlement_row_type, sub_ref_type,
  match_condition_json, mark_subtree_cost_required, priority, enabled, remark
)
SELECT
  'SPECIAL_PURCHASE_ROLLUP_PURPLE_COPPER_STRAIGHT_TUBE',
  '特殊子项品名上卷：紫铜直管',
  'SPECIAL_PURCHASE_ROLLUP',
  'ROLLUP_TO_PARENT',
  'SPECIAL_ROLLUP_PARENT',
  'SPECIAL_ROLLUP_CHILD',
  JSON_OBJECT('nodeConditions', JSON_ARRAY(JSON_OBJECT('field', 'material_name', 'op', 'LIKE', 'value', '紫铜直管'))),
  1,
  13,
  1,
  '末级采购件子项品名包含紫铜直管时，输出直接父件作为结算行'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM lp_bom_settlement_rule WHERE rule_code = 'SPECIAL_PURCHASE_ROLLUP_PURPLE_COPPER_STRAIGHT_TUBE');

INSERT INTO lp_bom_settlement_rule (
  rule_code, rule_name, rule_category, settlement_action, settlement_row_type, sub_ref_type,
  match_condition_json, mark_subtree_cost_required, priority, enabled, remark
)
SELECT
  'SPECIAL_PURCHASE_ROLLUP_DRAWN_COPPER_TUBE',
  '特殊子项品名上卷：拉制铜管',
  'SPECIAL_PURCHASE_ROLLUP',
  'ROLLUP_TO_PARENT',
  'SPECIAL_ROLLUP_PARENT',
  'SPECIAL_ROLLUP_CHILD',
  JSON_OBJECT('nodeConditions', JSON_ARRAY(JSON_OBJECT('field', 'material_name', 'op', 'LIKE', 'value', '拉制铜管'))),
  1,
  14,
  1,
  '末级采购件子项品名包含拉制铜管时，输出直接父件作为结算行'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM lp_bom_settlement_rule WHERE rule_code = 'SPECIAL_PURCHASE_ROLLUP_DRAWN_COPPER_TUBE');

INSERT INTO lp_bom_settlement_rule (
  rule_code, rule_name, rule_category, settlement_action, settlement_row_type, sub_ref_type,
  match_condition_json, mark_subtree_cost_required, priority, enabled, remark
)
SELECT
  'AUXILIARY_EXCLUDE_GREASE',
  '辅料排除：脂类',
  'AUXILIARY_EXCLUDE',
  'EXCLUDE',
  'EXCLUDED',
  NULL,
  JSON_OBJECT('nodeConditions', JSON_ARRAY(
    JSON_OBJECT('field', 'material_category_code', 'op', 'PREFIX', 'value', '18'),
    JSON_OBJECT('field', 'main_category_name', 'op', 'EQ', 'value', '脂类')
  )),
  0,
  40,
  1,
  '末级辅料主分类=脂类时不输出 BOM 结算行'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM lp_bom_settlement_rule WHERE rule_code = 'AUXILIARY_EXCLUDE_GREASE');

INSERT INTO lp_bom_settlement_rule (
  rule_code, rule_name, rule_category, settlement_action, settlement_row_type, sub_ref_type,
  match_condition_json, mark_subtree_cost_required, priority, enabled, remark
)
SELECT
  'AUXILIARY_EXCLUDE_OIL',
  '辅料排除：油类',
  'AUXILIARY_EXCLUDE',
  'EXCLUDE',
  'EXCLUDED',
  NULL,
  JSON_OBJECT('nodeConditions', JSON_ARRAY(
    JSON_OBJECT('field', 'material_category_code', 'op', 'PREFIX', 'value', '18'),
    JSON_OBJECT('field', 'main_category_name', 'op', 'EQ', 'value', '油类')
  )),
  0,
  41,
  1,
  '末级辅料主分类=油类时不输出 BOM 结算行'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM lp_bom_settlement_rule WHERE rule_code = 'AUXILIARY_EXCLUDE_OIL');

INSERT INTO lp_bom_settlement_rule (
  rule_code, rule_name, rule_category, settlement_action, settlement_row_type, sub_ref_type,
  match_condition_json, mark_subtree_cost_required, priority, enabled, remark
)
SELECT
  'AUXILIARY_EXCLUDE_ADHESIVE',
  '辅料排除：胶黏剂类',
  'AUXILIARY_EXCLUDE',
  'EXCLUDE',
  'EXCLUDED',
  NULL,
  JSON_OBJECT('nodeConditions', JSON_ARRAY(
    JSON_OBJECT('field', 'material_category_code', 'op', 'PREFIX', 'value', '18'),
    JSON_OBJECT('field', 'main_category_name', 'op', 'EQ', 'value', '胶黏剂类')
  )),
  0,
  42,
  1,
  '末级辅料主分类=胶黏剂类时不输出 BOM 结算行'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM lp_bom_settlement_rule WHERE rule_code = 'AUXILIARY_EXCLUDE_ADHESIVE');

INSERT INTO lp_bom_byproduct_cost_rule (
  rule_code, rule_name, rule_category, add_condition_type, settlement_row_type,
  match_condition_json, priority, enabled, remark
)
SELECT
  'BYPRODUCT_EXTRA_WHEN_NO_SCRAP_REF',
  '副产品未命中废料映射时输出结算行',
  'BYPRODUCT_EXTRA',
  'NO_SCRAP_REF_MATCH',
  'BYPRODUCT_EXTRA',
  JSON_OBJECT('byproductConditions', JSON_ARRAY(JSON_OBJECT('field', 'shape_attr', 'op', 'EQ', 'value', '制造件'))),
  10,
  0,
  'BSR-06 验收前默认关闭；当前制造件存在 U9 主制造副产品，且下层原材料未命中 lp_material_scrap_ref 时，副产品额外输出为结算行'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM lp_bom_byproduct_cost_rule WHERE rule_code = 'BYPRODUCT_EXTRA_WHEN_NO_SCRAP_REF');

DROP PROCEDURE IF EXISTS v146_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v146_add_index_if_not_exists;
