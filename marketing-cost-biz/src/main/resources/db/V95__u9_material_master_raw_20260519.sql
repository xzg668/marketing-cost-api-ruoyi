-- =============================================================================
-- V95  U9 料品主档 raw 表字段注释对齐料品档案20260519.xlsx           2026-05-19
--
-- 背景：
--   本次 U9 导出的“料品档案20260519.xlsx”仍是 63 个物理列，但字段顺序已和
--   20260427 旧文件不同：默认主供应商移动到 Excel 第19列，并新增第63列
--   “全局段3(理论净重)”。因此后续导入必须按表头映射，不能继续按旧列序 C0-C61
--   写入，否则会污染采购提前期、海关字段、材质、净重等关键数据。
--
--   本迁移只修正 lp_material_master_raw 的字段契约：
--     1. 补齐全局段3(理论净重)；
--     2. 补齐来源、来源批次、映射版本、有效批次标识，预留未来 U9 API/中台/MQ 接入；
--     3. 将 U9 字段注释改成“Excel第N列 表头”口径，和本次 Excel 第2行表头一致。
--
-- 幂等：
--   ADD COLUMN / ADD INDEX 先查 information_schema；MODIFY COMMENT 可重复执行。
-- =============================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS _v95_material_raw_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS _v95_material_raw_add_index_if_not_exists;
DROP PROCEDURE IF EXISTS _v95_material_raw_modify_column_if_exists;
DROP PROCEDURE IF EXISTS _v95_material_raw_alter_table_if_exists;

DELIMITER //

CREATE PROCEDURE _v95_material_raw_add_column_if_not_exists(
  IN p_column_name VARCHAR(64),
  IN p_column_definition TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'lp_material_master_raw'
  ) AND NOT EXISTS (
    SELECT 1
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'lp_material_master_raw'
      AND COLUMN_NAME = p_column_name
  ) THEN
    SET @v95_add_column_sql = CONCAT('ALTER TABLE lp_material_master_raw ', p_column_definition);
    PREPARE v95_add_column_stmt FROM @v95_add_column_sql;
    EXECUTE v95_add_column_stmt;
    DEALLOCATE PREPARE v95_add_column_stmt;
  END IF;
END //

CREATE PROCEDURE _v95_material_raw_add_index_if_not_exists(
  IN p_index_name VARCHAR(64),
  IN p_index_definition TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'lp_material_master_raw'
  ) AND NOT EXISTS (
    SELECT 1
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'lp_material_master_raw'
      AND INDEX_NAME = p_index_name
  ) THEN
    SET @v95_add_index_sql = CONCAT('ALTER TABLE lp_material_master_raw ', p_index_definition);
    PREPARE v95_add_index_stmt FROM @v95_add_index_sql;
    EXECUTE v95_add_index_stmt;
    DEALLOCATE PREPARE v95_add_index_stmt;
  END IF;
END //

CREATE PROCEDURE _v95_material_raw_modify_column_if_exists(
  IN p_column_name VARCHAR(64),
  IN p_column_definition TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'lp_material_master_raw'
      AND COLUMN_NAME = p_column_name
  ) THEN
    SET @v95_modify_column_sql =
      CONCAT('ALTER TABLE lp_material_master_raw MODIFY COLUMN `', p_column_name, '` ', p_column_definition);
    PREPARE v95_modify_column_stmt FROM @v95_modify_column_sql;
    EXECUTE v95_modify_column_stmt;
    DEALLOCATE PREPARE v95_modify_column_stmt;
  END IF;
END //

CREATE PROCEDURE _v95_material_raw_alter_table_if_exists(
  IN p_table_definition TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'lp_material_master_raw'
  ) THEN
    SET @v95_alter_table_sql = CONCAT('ALTER TABLE lp_material_master_raw ', p_table_definition);
    PREPARE v95_alter_table_stmt FROM @v95_alter_table_sql;
    EXECUTE v95_alter_table_stmt;
    DEALLOCATE PREPARE v95_alter_table_stmt;
  END IF;
END //

DELIMITER ;

CALL _v95_material_raw_add_column_if_not_exists(
  'global_seg_3_theoretical_net_weight',
  'ADD COLUMN global_seg_3_theoretical_net_weight VARCHAR(255) NULL COMMENT ''Excel第63列 全局段3(理论净重)'' AFTER mrp_purchase_pre_lead_time'
);
CALL _v95_material_raw_add_column_if_not_exists(
  'source_type',
  'ADD COLUMN source_type VARCHAR(32) NOT NULL DEFAULT ''EXCEL'' COMMENT ''来源类型：EXCEL/API/U9_API/MIDDLE_PLATFORM/MQ/SCHEDULE/MANUAL；未来U9接口或中台接入复用'' AFTER import_batch_id'
);
CALL _v95_material_raw_add_column_if_not_exists(
  'source_batch_no',
  'ADD COLUMN source_batch_no VARCHAR(128) NULL COMMENT ''来源系统批次号/接口流水号；Excel阶段可等于导入批次，未来U9接口接入用于追溯'' AFTER source_type'
);
CALL _v95_material_raw_add_column_if_not_exists(
  'mapping_version',
  'ADD COLUMN mapping_version VARCHAR(64) NOT NULL DEFAULT ''U9_ITEM_MASTER_20260519'' COMMENT ''字段映射版本；本次Excel模板为U9_ITEM_MASTER_20260519'' AFTER source_batch_no'
);
CALL _v95_material_raw_add_column_if_not_exists(
  'active_flag',
  'ADD COLUMN active_flag TINYINT NOT NULL DEFAULT 1 COMMENT ''有效批次标识：1=参与最新有效批次查询，0=历史归档；未来接口批次切换时维护'' AFTER mapping_version'
);

CALL _v95_material_raw_modify_column_if_exists('material_code', 'VARCHAR(64) NOT NULL COMMENT ''Excel第6列 物料代码*''');
CALL _v95_material_raw_modify_column_if_exists('finance_category', 'VARCHAR(255) NULL COMMENT ''Excel第1列 财务分类''');
CALL _v95_material_raw_modify_column_if_exists('purchase_category', 'VARCHAR(255) NULL COMMENT ''Excel第2列 采购分类''');
CALL _v95_material_raw_modify_column_if_exists('production_category', 'VARCHAR(255) NULL COMMENT ''Excel第3列 生产分类''');
CALL _v95_material_raw_modify_column_if_exists('sales_category', 'VARCHAR(255) NULL COMMENT ''Excel第4列 销售分类''');
CALL _v95_material_raw_modify_column_if_exists('bare_code', 'VARCHAR(255) NULL COMMENT ''Excel第5列 裸品编码''');
CALL _v95_material_raw_modify_column_if_exists('material_name', 'VARCHAR(255) NULL COMMENT ''Excel第7列 物料名称*''');
CALL _v95_material_raw_modify_column_if_exists('material_spec', 'VARCHAR(255) NULL COMMENT ''Excel第8列 物料规格''');
CALL _v95_material_raw_modify_column_if_exists('material_model', 'VARCHAR(255) NULL COMMENT ''Excel第9列 物料型号''');
CALL _v95_material_raw_modify_column_if_exists('drawing_no', 'VARCHAR(255) NULL COMMENT ''Excel第10列 物料图号''');
CALL _v95_material_raw_modify_column_if_exists('main_category_code', 'VARCHAR(255) NULL COMMENT ''Excel第11列 主分类代码''');
CALL _v95_material_raw_modify_column_if_exists('main_category_name', 'VARCHAR(255) NULL COMMENT ''Excel第12列 主分类名称''');
CALL _v95_material_raw_modify_column_if_exists('unit', 'VARCHAR(255) NULL COMMENT ''Excel第13列 计量单位''');
CALL _v95_material_raw_modify_column_if_exists('shape_attr', 'VARCHAR(255) NULL COMMENT ''Excel第14列 U9物料形态属性''');
CALL _v95_material_raw_modify_column_if_exists('min_eco_batch', 'VARCHAR(255) NULL COMMENT ''Excel第15列 最小经济批量''');
CALL _v95_material_raw_modify_column_if_exists('department_code', 'VARCHAR(255) NULL COMMENT ''Excel第16列 部门代码''');
CALL _v95_material_raw_modify_column_if_exists('department_name', 'VARCHAR(255) NULL COMMENT ''Excel第17列 部门名称''');
CALL _v95_material_raw_modify_column_if_exists('production_division', 'VARCHAR(255) NULL COMMENT ''Excel第18列 生产事业部名称''');
CALL _v95_material_raw_modify_column_if_exists('default_supplier', 'VARCHAR(255) NULL COMMENT ''Excel第19列 默认主供应商''');
CALL _v95_material_raw_modify_column_if_exists('purchase_lead_time', 'VARCHAR(255) NULL COMMENT ''Excel第20列 采购处理提前期''');
CALL _v95_material_raw_modify_column_if_exists('purchase_post_lead_time', 'VARCHAR(255) NULL COMMENT ''Excel第21列 采购后处理提前期''');
CALL _v95_material_raw_modify_column_if_exists('legacy_u9_code', 'VARCHAR(255) NULL COMMENT ''Excel第22列 老U9物料代码''');
CALL _v95_material_raw_modify_column_if_exists('global_seg_14_customs_unit', 'VARCHAR(255) NULL COMMENT ''Excel第23列 全局段14(海关单位)''');
CALL _v95_material_raw_modify_column_if_exists('global_seg_15_package_size', 'VARCHAR(255) NULL COMMENT ''Excel第24列 全局段15(包装尺寸)''');
CALL _v95_material_raw_modify_column_if_exists('global_seg_17_replace_strategy', 'VARCHAR(255) NULL COMMENT ''Excel第25列 全局段17(替代策略)''');
CALL _v95_material_raw_modify_column_if_exists('global_seg_18_purchase_type', 'VARCHAR(255) NULL COMMENT ''Excel第26列 全局段18(采购类型)''');
CALL _v95_material_raw_modify_column_if_exists('global_seg_19_in_out_ratio', 'VARCHAR(255) NULL COMMENT ''Excel第27列 全局段19(内外采比例)''');
CALL _v95_material_raw_modify_column_if_exists('global_seg_2_logistics_type', 'VARCHAR(255) NULL COMMENT ''Excel第28列 全局段2(物流采购类型)''');
CALL _v95_material_raw_modify_column_if_exists('global_seg_20_internal_threshold', 'VARCHAR(255) NULL COMMENT ''Excel第29列 全局段20(内部采购阈值)''');
CALL _v95_material_raw_modify_column_if_exists('private_seg_21_customs_name', 'VARCHAR(255) NULL COMMENT ''Excel第30列 私有段21(海关名称)''');
CALL _v95_material_raw_modify_column_if_exists('private_seg_22_customs_code', 'VARCHAR(255) NULL COMMENT ''Excel第31列 私有段22(海关编码)''');
CALL _v95_material_raw_modify_column_if_exists('private_seg_23_customs_desc', 'VARCHAR(255) NULL COMMENT ''Excel第32列 私有段23(海关描述)''');
CALL _v95_material_raw_modify_column_if_exists('private_seg_24_product_property', 'VARCHAR(255) NULL COMMENT ''Excel第33列 私有段24(产品属性)''');
CALL _v95_material_raw_modify_column_if_exists('private_seg_25_daily_capacity', 'VARCHAR(255) NULL COMMENT ''Excel第34列 私有段25(日产能)''');
CALL _v95_material_raw_modify_column_if_exists('private_seg_26_lead_time', 'VARCHAR(255) NULL COMMENT ''Excel第35列 私有段26(加工周期)''');
CALL _v95_material_raw_modify_column_if_exists('global_seg_3_status', 'VARCHAR(255) NULL COMMENT ''Excel第36列 全局段3(验证/正式)''');
CALL _v95_material_raw_modify_column_if_exists('global_seg_4_material', 'VARCHAR(255) NULL COMMENT ''Excel第37列 全局段4(材质)''');
CALL _v95_material_raw_modify_column_if_exists('global_seg_5_net_weight', 'VARCHAR(255) NULL COMMENT ''Excel第38列 全局段5(净重)''');
CALL _v95_material_raw_modify_column_if_exists('global_seg_6_valid_period', 'VARCHAR(255) NULL COMMENT ''Excel第39列 全局段6(有效期)''');
CALL _v95_material_raw_modify_column_if_exists('global_seg_7_product_property_class', 'VARCHAR(255) NULL COMMENT ''Excel第40列 全局段7(产品属性分类)''');
CALL _v95_material_raw_modify_column_if_exists('global_seg_8_loss_rate', 'VARCHAR(255) NULL COMMENT ''Excel第41列 全局段8(净损失率)''');
CALL _v95_material_raw_modify_column_if_exists('global_seg_9_gross_weight', 'VARCHAR(255) NULL COMMENT ''Excel第42列 全局段9(单品毛重)''');
CALL _v95_material_raw_modify_column_if_exists('purchase_multiple', 'VARCHAR(255) NULL COMMENT ''Excel第43列 采购倍量''');
CALL _v95_material_raw_modify_column_if_exists('min_order_qty', 'VARCHAR(255) NULL COMMENT ''Excel第44列 最小叫货量''');
CALL _v95_material_raw_modify_column_if_exists('default_buyer', 'VARCHAR(255) NULL COMMENT ''Excel第45列 默认采购员''');
CALL _v95_material_raw_modify_column_if_exists('plan_method', 'VARCHAR(255) NULL COMMENT ''Excel第46列 计划方法''');
CALL _v95_material_raw_modify_column_if_exists('forecast_control_type', 'VARCHAR(255) NULL COMMENT ''Excel第47列 预测控制类型''');
CALL _v95_material_raw_modify_column_if_exists('demand_trace', 'VARCHAR(255) NULL COMMENT ''Excel第48列 是否需求追溯''');
CALL _v95_material_raw_modify_column_if_exists('demand_category_control', 'VARCHAR(255) NULL COMMENT ''Excel第49列 按照需求分类控制''');
CALL _v95_material_raw_modify_column_if_exists('demand_category_compare_rule', 'VARCHAR(255) NULL COMMENT ''Excel第50列 需求分类对比规则''');
CALL _v95_material_raw_modify_column_if_exists('default_planner', 'VARCHAR(255) NULL COMMENT ''Excel第51列 默认计划员''');
CALL _v95_material_raw_modify_column_if_exists('engineering_change_control', 'VARCHAR(255) NULL COMMENT ''Excel第52列 工程变更控制''');
CALL _v95_material_raw_modify_column_if_exists('allow_over_pick', 'VARCHAR(255) NULL COMMENT ''Excel第53列 允许超额领料''');
CALL _v95_material_raw_modify_column_if_exists('prepare_over_type', 'VARCHAR(255) NULL COMMENT ''Excel第54列 备料超额类型''');
CALL _v95_material_raw_modify_column_if_exists('over_complete_type', 'VARCHAR(255) NULL COMMENT ''Excel第55列 可超量完工类型''');
CALL _v95_material_raw_modify_column_if_exists('over_complete_ratio', 'VARCHAR(255) NULL COMMENT ''Excel第56列 完工超额比例''');
CALL _v95_material_raw_modify_column_if_exists('inventory_planning_method', 'VARCHAR(255) NULL COMMENT ''Excel第57列 库存规划方法''');
CALL _v95_material_raw_modify_column_if_exists('code_inventory_account', 'VARCHAR(255) NULL COMMENT ''Excel第58列 番号存货核算''');
CALL _v95_material_raw_modify_column_if_exists('cost_element', 'VARCHAR(255) NULL COMMENT ''Excel第59列 成本要素''');
CALL _v95_material_raw_modify_column_if_exists('producible', 'VARCHAR(255) NULL COMMENT ''Excel第60列 可生产''');
CALL _v95_material_raw_modify_column_if_exists('purchase_receive_principle', 'VARCHAR(255) NULL COMMENT ''Excel第61列 收货原则；旧表头别名：料品采购相关信息.收货原则''');
CALL _v95_material_raw_modify_column_if_exists('mrp_purchase_pre_lead_time', 'VARCHAR(255) NULL COMMENT ''Excel第62列 采购预处理提前期(天)；旧表头别名：料品MRP相关信息.采购预处理提前期(天)''');
CALL _v95_material_raw_modify_column_if_exists('global_seg_3_theoretical_net_weight', 'VARCHAR(255) NULL COMMENT ''Excel第63列 全局段3(理论净重)''');

CALL _v95_material_raw_modify_column_if_exists('import_batch_id', 'VARCHAR(64) NOT NULL COMMENT ''导入批次ID：同一文件或接口批次唯一标识''');
CALL _v95_material_raw_modify_column_if_exists('source_type', 'VARCHAR(32) NOT NULL DEFAULT ''EXCEL'' COMMENT ''来源类型：EXCEL/API/U9_API/MIDDLE_PLATFORM/MQ/SCHEDULE/MANUAL；未来U9接口或中台接入复用''');
CALL _v95_material_raw_modify_column_if_exists('source_batch_no', 'VARCHAR(128) NULL COMMENT ''来源系统批次号/接口流水号；Excel阶段可等于导入批次，未来U9接口接入用于追溯''');
CALL _v95_material_raw_modify_column_if_exists('mapping_version', 'VARCHAR(64) NOT NULL DEFAULT ''U9_ITEM_MASTER_20260519'' COMMENT ''字段映射版本；本次Excel模板为U9_ITEM_MASTER_20260519''');
CALL _v95_material_raw_modify_column_if_exists('active_flag', 'TINYINT NOT NULL DEFAULT 1 COMMENT ''有效批次标识：1=参与最新有效批次查询，0=历史归档；未来接口批次切换时维护''');

CALL _v95_material_raw_add_index_if_not_exists('idx_raw_batch', 'ADD KEY idx_raw_batch (import_batch_id)');
CALL _v95_material_raw_add_index_if_not_exists('idx_raw_shape', 'ADD KEY idx_raw_shape (shape_attr)');
CALL _v95_material_raw_add_index_if_not_exists('idx_raw_cost_element', 'ADD KEY idx_raw_cost_element (cost_element)');
CALL _v95_material_raw_add_index_if_not_exists('idx_raw_main_cat', 'ADD KEY idx_raw_main_cat (main_category_code)');
CALL _v95_material_raw_add_index_if_not_exists('idx_raw_source_batch', 'ADD KEY idx_raw_source_batch (source_type, source_batch_no)');
CALL _v95_material_raw_add_index_if_not_exists('idx_raw_active_batch', 'ADD KEY idx_raw_active_batch (active_flag, import_batch_id)');

CALL _v95_material_raw_alter_table_if_exists(
  'COMMENT = ''U9料品主档原始导入表：字段注释按料品档案20260519.xlsx第2行表头和Excel 1-based列号维护'''
);

DROP PROCEDURE IF EXISTS _v95_material_raw_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS _v95_material_raw_add_index_if_not_exists;
DROP PROCEDURE IF EXISTS _v95_material_raw_modify_column_if_exists;
DROP PROCEDURE IF EXISTS _v95_material_raw_alter_table_if_exists;
