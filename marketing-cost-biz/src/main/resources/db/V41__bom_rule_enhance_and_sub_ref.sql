-- =============================================================================
-- V41  T8 · BOM 过滤规则复合条件 + 父件上卷子件关系表  2026-04-24
--
-- 改动：
--   1) bom_stop_drill_rule 增加 match_condition_json 列（JSON 复合条件）
--   2) 新建 lp_bom_costing_row_sub_ref 表（父件结算行 → 命中子件清单固化）
--   3) sys_dict_type + sys_dict_data 新增 bom_material_category 字典
--   4) 示例规则：紫铜类铜管组件 ROLLUP_TO_PARENT（复合条件 + 新 action）
--
-- 幂等：重跑不会报错。ALTER TABLE 用存储过程包一层做 column-exists 检查
-- （MySQL 8 不支持 ALTER TABLE ADD COLUMN IF NOT EXISTS，只能这么做）。
-- DELIMITER 块只能通过 mysql CLI 执行，JDBC 跑不了。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1a) bom_stop_drill_rule 加 match_condition_json 列（幂等）
-- -----------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS add_match_condition_json_v41;

DELIMITER //
CREATE PROCEDURE add_match_condition_json_v41()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE table_schema = DATABASE()
      AND table_name   = 'bom_stop_drill_rule'
      AND column_name  = 'match_condition_json'
  ) THEN
    ALTER TABLE bom_stop_drill_rule
      ADD COLUMN match_condition_json JSON DEFAULT NULL
      COMMENT 'T8 复合条件 JSON；非空时优先；空则用 match_type/match_value';
  END IF;
END //
DELIMITER ;

CALL add_match_condition_json_v41();
DROP PROCEDURE add_match_condition_json_v41;

-- -----------------------------------------------------------------------------
-- 1b) lp_bom_raw_hierarchy 加 material_category_1 / material_category_2 列
--     T8 规则复合条件里可能用到子件主分类（例：紫铜盘管），必须透传到 DWD 层
--     否则 matcher 要反查 u9_source 影响性能
-- -----------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS add_raw_hierarchy_category_cols_v41;

DELIMITER //
CREATE PROCEDURE add_raw_hierarchy_category_cols_v41()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE table_schema = DATABASE()
      AND table_name   = 'lp_bom_raw_hierarchy'
      AND column_name  = 'material_category_1'
  ) THEN
    ALTER TABLE lp_bom_raw_hierarchy
      ADD COLUMN material_category_1 VARCHAR(32) DEFAULT NULL
      COMMENT 'T8：U9 子件主分类第 1 列 从 u9_source 透传';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE table_schema = DATABASE()
      AND table_name   = 'lp_bom_raw_hierarchy'
      AND column_name  = 'material_category_2'
  ) THEN
    ALTER TABLE lp_bom_raw_hierarchy
      ADD COLUMN material_category_2 VARCHAR(32) DEFAULT NULL
      COMMENT 'T8：U9 子件主分类第 2 列 从 u9_source 透传';
  END IF;
END //
DELIMITER ;

CALL add_raw_hierarchy_category_cols_v41();
DROP PROCEDURE add_raw_hierarchy_category_cols_v41;

-- -----------------------------------------------------------------------------
-- 2) 子件关系固化表：父件 ROLLUP_TO_PARENT 时 Flatten 写入
--    T9 取价阶段直接读此表拿子件清单（不必再跑规则匹配）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS lp_bom_costing_row_sub_ref (
  id                     BIGINT       NOT NULL AUTO_INCREMENT,
  costing_row_id         BIGINT       NOT NULL COMMENT '父件 lp_bom_costing_row.id',
  sub_material_code      VARCHAR(32)  NOT NULL COMMENT '命中子件料号（T9 取价用）',
  sub_material_name      VARCHAR(128) DEFAULT NULL,
  sub_material_category  VARCHAR(32)  DEFAULT NULL COMMENT '命中时的 material_category_1',
  sub_qty_per_parent     DECIMAL(20,8) DEFAULT NULL COMMENT '子件相对父用量',
  sub_qty_per_top        DECIMAL(20,8) DEFAULT NULL COMMENT '累计到顶层用量',
  sub_raw_hierarchy_id   BIGINT       DEFAULT NULL COMMENT '追溯 raw_hierarchy.id',
  sub_path               VARCHAR(512) DEFAULT NULL,
  business_unit_type     VARCHAR(16)  DEFAULT NULL COMMENT 'V21 数据隔离',
  created_at             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  INDEX idx_costing_row (costing_row_id),
  INDEX idx_sub_material (sub_material_code),
  INDEX idx_bu_type (business_unit_type),
  CONSTRAINT fk_sub_ref_costing
    FOREIGN KEY (costing_row_id)
    REFERENCES lp_bom_costing_row(id)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='T8：BOM 父件结算行 → 命中子件清单固化表，供 T9 取价阶段直接读';

-- -----------------------------------------------------------------------------
-- 3) 字典：BOM 主分类（bom_material_category）
-- -----------------------------------------------------------------------------
INSERT INTO sys_dict_type (dict_name, dict_type, status, remark)
  SELECT 'BOM 主分类', 'bom_material_category', '0', 'T8 新增：规则 IN 条件的候选值'
  FROM DUAL
  WHERE NOT EXISTS (
    SELECT 1 FROM sys_dict_type WHERE dict_type = 'bom_material_category'
  );

-- 种子条目（业务可自行增删）
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 1, '紫铜盘管', '紫铜盘管', 'bom_material_category', '0', 'T8 种子' FROM DUAL
  WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='bom_material_category' AND dict_value='紫铜盘管');

INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 2, '紫铜直管', '紫铜直管', 'bom_material_category', '0', 'T8 种子' FROM DUAL
  WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='bom_material_category' AND dict_value='紫铜直管');

INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 3, '紫铜其他', '紫铜其他', 'bom_material_category', '0', 'T8 种子' FROM DUAL
  WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='bom_material_category' AND dict_value='紫铜其他');

INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 4, '铝盘管', '铝盘管', 'bom_material_category', '0', 'T8 种子' FROM DUAL
  WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='bom_material_category' AND dict_value='铝盘管');

INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 5, '铝直管', '铝直管', 'bom_material_category', '0', 'T8 种子' FROM DUAL
  WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='bom_material_category' AND dict_value='铝直管');

-- -----------------------------------------------------------------------------
-- 4) 示例规则：铜管组件类父件 ROLLUP_TO_PARENT
-- -----------------------------------------------------------------------------
INSERT INTO bom_stop_drill_rule (
  match_type, match_value, match_condition_json,
  drill_action, mark_subtree_cost_required, priority, enabled, remark
)
  SELECT
    'COMPOSITE',
    'copper-tube-assembly',
    '{"nodeConditions":[{"field":"cost_element_code","op":"EQ","value":"主要材料-原材料"}],"childConditions":[{"field":"material_category_1","op":"IN","values":["紫铜盘管","紫铜直管","紫铜其他"]}]}',
    'ROLLUP_TO_PARENT',
    1,
    5,
    1,
    'T8 示例：铜管组件类父件作结算行 价由 T9 取价阶段按子件加总'
  FROM DUAL
  WHERE NOT EXISTS (
    SELECT 1 FROM bom_stop_drill_rule
    WHERE drill_action='ROLLUP_TO_PARENT' AND match_value='copper-tube-assembly'
  );

-- V41 结束
