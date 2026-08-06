-- =====================================================================
-- V199: 报价 BOM 标准件/替代件选择基础结构
--
-- 本迁移只增加结构：
-- 1. 正式 BOM 层级透传标准/替代类型及稳定组键；
-- 2. 新增报价作用域内的选择版本表。
--
-- 不回填旧层级，不修改 U9 源数据、报价结算行或报价确认记录。
-- =====================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS v199_add_column_if_not_exists;
DELIMITER //
CREATE PROCEDURE v199_add_column_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_column_name VARCHAR(64),
  IN p_column_definition TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND COLUMN_NAME = p_column_name
  ) THEN
    SET @v199_column_ddl = CONCAT(
      'ALTER TABLE `', p_table_name, '` ADD COLUMN `', p_column_name, '` ',
      p_column_definition
    );
    PREPARE v199_column_stmt FROM @v199_column_ddl;
    EXECUTE v199_column_stmt;
    DEALLOCATE PREPARE v199_column_stmt;
  END IF;
END//
DELIMITER ;

DROP PROCEDURE IF EXISTS v199_add_index_if_not_exists;
DELIMITER //
CREATE PROCEDURE v199_add_index_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_index_name VARCHAR(64),
  IN p_index_definition TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
      FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND INDEX_NAME = p_index_name
  ) THEN
    SET @v199_index_ddl = CONCAT(
      'ALTER TABLE `', p_table_name, '` ADD ', p_index_definition
    );
    PREPARE v199_index_stmt FROM @v199_index_ddl;
    EXECUTE v199_index_stmt;
    DEALLOCATE PREPARE v199_index_stmt;
  END IF;
END//
DELIMITER ;

CALL v199_add_column_if_not_exists(
  'lp_bom_raw_hierarchy',
  'child_type',
  'VARCHAR(16) NULL COMMENT ''U9子项类型：标准/替代'''
);
CALL v199_add_column_if_not_exists(
  'lp_bom_raw_hierarchy',
  'alternative_group_key',
  'CHAR(64) NULL COMMENT ''同一BOM位置的标准/替代组稳定键'''
);
CALL v199_add_index_if_not_exists(
  'lp_bom_raw_hierarchy',
  'idx_bom_raw_alt_group',
  'KEY `idx_bom_raw_alt_group` '
    '(`price_org_code`, `top_product_code`, `bom_purpose`, `alternative_group_key`)'
);

CREATE TABLE IF NOT EXISTS `lp_quote_bom_alternative_selection` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `selection_no` VARCHAR(64) NOT NULL COMMENT '选择记录号',

  `oa_no` VARCHAR(64) NOT NULL COMMENT 'OA单号',
  `oa_form_item_id` BIGINT NOT NULL COMMENT 'OA产品明细行ID',
  `top_product_code` VARCHAR(64) NOT NULL COMMENT '顶层产品料号',
  `period_month` CHAR(7) NOT NULL COMMENT '核算月份YYYY-MM',
  `price_org_code` VARCHAR(32) NOT NULL COMMENT '报价组织',

  `alternative_group_key` CHAR(64) NOT NULL COMMENT '替代组稳定键',
  `parent_path` VARCHAR(2000) NULL COMMENT '替代组直接父件路径快照',
  `parent_material_code` VARCHAR(64) NOT NULL COMMENT '直接父件料号',
  `parent_material_name` VARCHAR(255) NULL COMMENT '直接父件名称',
  `child_seq` INT NULL COMMENT 'U9子项序号',
  `process_seq` VARCHAR(32) NULL COMMENT 'U9工序号',
  `bom_purpose` VARCHAR(32) NULL COMMENT 'BOM生产目的',
  `bom_version` VARCHAR(64) NULL COMMENT 'BOM版本',
  `source_effective_from` DATE NULL COMMENT '来源BOM生效日期',
  `source_effective_to` DATE NULL COMMENT '来源BOM失效日期',

  `standard_material_code` VARCHAR(64) NOT NULL COMMENT '当时的标准件料号',
  `selected_material_code` VARCHAR(64) NOT NULL COMMENT '当前版本选中的料号',
  `selected_child_type` VARCHAR(16) NOT NULL COMMENT 'STANDARD/ALTERNATIVE',
  `selection_source` VARCHAR(32) NOT NULL
    COMMENT 'AUTO_STANDARD/MANUAL_STANDARD/MANUAL_ALTERNATIVE',

  `selection_version` INT NOT NULL COMMENT '同报价同替代组选择版本',
  `selection_status` VARCHAR(16) NOT NULL COMMENT 'ACTIVE/SUPERSEDED/STALE',
  `current_slot` TINYINT NULL COMMENT '当前有效行固定为1，历史行为空',

  `candidate_snapshot_json` JSON NULL COMMENT '选择时标准及替代候选快照',
  `source_import_batch_id` VARCHAR(128) NULL COMMENT '来源导入批次',
  `source_build_batch_id` VARCHAR(128) NULL COMMENT '来源层级构建批次',

  `selected_by` VARCHAR(64) NULL COMMENT '选择人',
  `selected_at` DATETIME NULL COMMENT '选择时间',
  `selection_remark` VARCHAR(1000) NULL COMMENT '选择说明',
  `business_unit_type` VARCHAR(32) NULL COMMENT '业务单元',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
    ON UPDATE CURRENT_TIMESTAMP,

  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='报价BOM标准件/替代件选择及版本历史';

CALL v199_add_index_if_not_exists(
  'lp_quote_bom_alternative_selection',
  'uk_quote_alt_selection_no',
  'UNIQUE KEY `uk_quote_alt_selection_no` (`selection_no`)'
);
CALL v199_add_index_if_not_exists(
  'lp_quote_bom_alternative_selection',
  'uk_quote_alt_selection_version',
  'UNIQUE KEY `uk_quote_alt_selection_version` '
    '(`oa_no`, `oa_form_item_id`, `top_product_code`, `period_month`, '
    '`alternative_group_key`, `selection_version`)'
);
CALL v199_add_index_if_not_exists(
  'lp_quote_bom_alternative_selection',
  'uk_quote_alt_selection_current',
  'UNIQUE KEY `uk_quote_alt_selection_current` '
    '(`oa_no`, `oa_form_item_id`, `top_product_code`, `period_month`, '
    '`alternative_group_key`, `current_slot`)'
);
CALL v199_add_index_if_not_exists(
  'lp_quote_bom_alternative_selection',
  'idx_quote_alt_selection_item',
  'KEY `idx_quote_alt_selection_item` '
    '(`oa_no`, `oa_form_item_id`, `top_product_code`, `period_month`)'
);
CALL v199_add_index_if_not_exists(
  'lp_quote_bom_alternative_selection',
  'idx_quote_alt_selection_selected',
  'KEY `idx_quote_alt_selection_selected` (`selected_material_code`)'
);
CALL v199_add_index_if_not_exists(
  'lp_quote_bom_alternative_selection',
  'idx_quote_alt_selection_status',
  'KEY `idx_quote_alt_selection_status` (`selection_status`)'
);

DROP PROCEDURE IF EXISTS v199_add_index_if_not_exists;
DROP PROCEDURE IF EXISTS v199_add_column_if_not_exists;
