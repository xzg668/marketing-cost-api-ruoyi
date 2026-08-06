-- =============================================================================
-- V202: 报价最终有效 BOM 节点和料品报价形态规则
-- -----------------------------------------------------------------------------
-- 本迁移只建立结构和访问契约：
--   1. 新增最终有效 BOM 节点表；
--   2. 新增料品报价形态规则表；
--   3. 给月度卡片、确认和替代选择补最终构建追溯字段。
--
-- 不生成最终树，不回填虚构构建，不修改历史选择、确认、计价行或 U9 BOM。
-- =============================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS v202_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v202_add_index_if_not_exists;

DELIMITER //

CREATE PROCEDURE v202_add_column_if_not_exists(
  IN p_table_name VARCHAR(128),
  IN p_column_name VARCHAR(128),
  IN p_column_ddl TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1
      FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
  ) AND NOT EXISTS (
    SELECT 1
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND COLUMN_NAME = p_column_name
  ) THEN
    SET @v202_add_column_sql = CONCAT(
      'ALTER TABLE ', p_table_name, ' ADD COLUMN ', p_column_ddl
    );
    PREPARE v202_add_column_stmt FROM @v202_add_column_sql;
    EXECUTE v202_add_column_stmt;
    DEALLOCATE PREPARE v202_add_column_stmt;
  END IF;
END //

CREATE PROCEDURE v202_add_index_if_not_exists(
  IN p_table_name VARCHAR(128),
  IN p_index_name VARCHAR(128),
  IN p_index_ddl TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1
      FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
  ) AND NOT EXISTS (
    SELECT 1
      FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND INDEX_NAME = p_index_name
  ) THEN
    SET @v202_add_index_sql = CONCAT(
      'ALTER TABLE ', p_table_name, ' ADD ', p_index_ddl
    );
    PREPARE v202_add_index_stmt FROM @v202_add_index_sql;
    EXECUTE v202_add_index_stmt;
    DEALLOCATE PREPARE v202_add_index_stmt;
  END IF;
END //

DELIMITER ;

CREATE TABLE IF NOT EXISTS lp_quote_effective_bom_node (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  build_batch_id VARCHAR(64) NOT NULL COMMENT '最终有效BOM构建编号',
  origin_monthly_snapshot_id BIGINT NOT NULL COMMENT '首次创建本构建的月度卡片',
  effective_variant_hash VARCHAR(64) NOT NULL COMMENT '最终结果指纹',

  top_product_code VARCHAR(64) NOT NULL COMMENT '顶层产品料号',
  cost_period_month CHAR(7) NOT NULL COMMENT '核算月份yyyy-MM',
  price_org_code VARCHAR(64) NOT NULL COMMENT 'U9 BOM组织编码：210商用、220板换',

  node_key VARCHAR(128) NOT NULL COMMENT '本构建内节点唯一键',
  parent_node_key VARCHAR(128) NULL COMMENT '父节点键',
  node_level INT NOT NULL COMMENT '层级，顶层为0',
  sort_seq INT NOT NULL DEFAULT 0 COMMENT '同级排序',
  node_path VARCHAR(2000) NOT NULL COMMENT '节点路径',

  material_code VARCHAR(64) NOT NULL COMMENT '料号',
  material_name VARCHAR(255) NULL COMMENT '名称快照',
  material_spec VARCHAR(255) NULL COMMENT '规格快照',
  material_model VARCHAR(255) NULL COMMENT '型号快照',
  material_unit VARCHAR(32) NULL COMMENT '单位快照',
  qty_per_parent DECIMAL(24,8) NOT NULL COMMENT '相对父级用量',
  qty_per_top DECIMAL(24,8) NOT NULL COMMENT '相对顶层累计用量',

  source_material_shape VARCHAR(32) NULL COMMENT 'U9原始形态',
  effective_material_shape VARCHAR(32) NOT NULL COMMENT '最终报价形态',
  shape_resolution_source VARCHAR(32) NOT NULL
    COMMENT 'U9/FIXED_POLICY/SUPPLIER_RATIO',
  shape_policy_id BIGINT NULL COMMENT '命中的形态规则ID',
  shape_policy_fingerprint VARCHAR(64) NULL COMMENT '规则内容指纹',

  selected_supplier_ratio_id BIGINT NULL COMMENT '供货比例证据ID',
  selected_supplier_code VARCHAR(64) NULL COMMENT '主供应商编码快照',
  selected_supplier_name VARCHAR(255) NULL COMMENT '主供应商名称快照',
  selected_supply_ratio DECIMAL(12,6) NULL COMMENT '主供应商比例快照',

  alternative_group_key VARCHAR(128) NULL COMMENT '标准替代组键',
  alternative_child_type VARCHAR(16) NULL COMMENT 'STANDARD/ALTERNATIVE',
  alternative_selection_id BIGINT NULL COMMENT '首次选择证据ID',
  alternative_selection_source VARCHAR(32) NULL
    COMMENT 'AUTO_STANDARD/MANUAL_STANDARD/MANUAL_ALTERNATIVE/INHERITED_MONTHLY',

  source_bom_type VARCHAR(32) NOT NULL COMMENT 'U9/SUPPLEMENT',
  source_bom_batch_id VARCHAR(64) NULL COMMENT '原始候选BOM批次',
  source_hierarchy_id BIGINT NULL COMMENT '原始层级节点ID',
  source_node_path VARCHAR(2000) NULL COMMENT '原始节点路径',

  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  created_by BIGINT NULL COMMENT '创建人',
  PRIMARY KEY (id),
  UNIQUE KEY uk_build_node (build_batch_id, node_key),
  KEY idx_variant_hash (effective_variant_hash),
  KEY idx_top_month (top_product_code, cost_period_month),
  KEY idx_material_code (material_code),
  KEY idx_origin_snapshot (origin_monthly_snapshot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='报价最终有效BOM节点';

CREATE TABLE IF NOT EXISTS lp_material_quote_shape_policy (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  material_org_code VARCHAR(64) NOT NULL COMMENT '料品组织',
  material_code VARCHAR(64) NOT NULL COMMENT '料号',
  material_name VARCHAR(255) NULL COMMENT '名称快照',
  material_spec VARCHAR(255) NULL COMMENT '规格快照',
  material_model VARCHAR(255) NULL COMMENT '型号快照',

  policy_mode VARCHAR(32) NOT NULL COMMENT 'FIXED/SUPPLIER_RATIO',
  fixed_target_shape VARCHAR(32) NULL COMMENT '固定目标形态',
  condition_config_json JSON NULL COMMENT '供应商等判断条件',
  action_config_json JSON NULL COMMENT '目标形态及排除直接子件',

  effective_from_month CHAR(7) NOT NULL COMMENT '生效起始月份',
  effective_to_month CHAR(7) NULL COMMENT '生效结束月份，空为长期',
  enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
  remark VARCHAR(1000) NULL COMMENT '业务说明',

  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  created_by BIGINT NULL COMMENT '创建人',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
    ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  updated_by BIGINT NULL COMMENT '更新人',
  PRIMARY KEY (id),
  KEY idx_material_month (material_org_code, material_code, effective_from_month, effective_to_month, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='料品报价形态规则';

CALL v202_add_column_if_not_exists(
  'lp_quote_bom_monthly_snapshot',
  'freeze_status',
  'freeze_status VARCHAR(16) NOT NULL DEFAULT ''DRAFT'' COMMENT ''DRAFT/FROZEN'''
);
CALL v202_add_column_if_not_exists(
  'lp_quote_bom_monthly_snapshot',
  'effective_build_batch_id',
  'effective_build_batch_id VARCHAR(64) NULL COMMENT ''冻结后指向最终有效BOM构建'''
);
CALL v202_add_column_if_not_exists(
  'lp_quote_bom_monthly_snapshot',
  'effective_variant_hash',
  'effective_variant_hash VARCHAR(64) NULL COMMENT ''最终结果指纹'''
);
CALL v202_add_column_if_not_exists(
  'lp_quote_bom_monthly_snapshot',
  'frozen_at',
  'frozen_at DATETIME NULL COMMENT ''冻结时间'''
);
CALL v202_add_column_if_not_exists(
  'lp_quote_bom_monthly_snapshot',
  'frozen_by',
  'frozen_by BIGINT NULL COMMENT ''冻结人'''
);

CALL v202_add_column_if_not_exists(
  'lp_quote_bom_confirmation',
  'costing_build_batch_id',
  'costing_build_batch_id VARCHAR(64) NULL COMMENT ''确认时最终有效BOM构建编号'''
);

CALL v202_add_column_if_not_exists(
  'lp_quote_bom_alternative_selection',
  'inherited_monthly_snapshot_id',
  'inherited_monthly_snapshot_id BIGINT NULL COMMENT ''继承来源月度冻结卡片ID'''
);

CALL v202_add_index_if_not_exists(
  'lp_quote_bom_monthly_snapshot',
  'idx_quote_bom_monthly_effective_build',
  'KEY idx_quote_bom_monthly_effective_build (effective_build_batch_id)'
);
CALL v202_add_index_if_not_exists(
  'lp_quote_bom_monthly_snapshot',
  'idx_quote_bom_monthly_variant_hash',
  'KEY idx_quote_bom_monthly_variant_hash (effective_variant_hash)'
);
CALL v202_add_index_if_not_exists(
  'lp_quote_bom_confirmation',
  'idx_quote_bom_confirm_build',
  'KEY idx_quote_bom_confirm_build (costing_build_batch_id)'
);
CALL v202_add_index_if_not_exists(
  'lp_quote_bom_alternative_selection',
  'idx_quote_alt_inherited_snapshot',
  'KEY idx_quote_alt_inherited_snapshot (inherited_monthly_snapshot_id)'
);

DROP PROCEDURE IF EXISTS v202_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v202_add_index_if_not_exists;
