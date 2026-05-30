-- =============================================================================
-- V142: 报价产品 BOM 准备补录层和来源追溯模型
-- -----------------------------------------------------------------------------
-- 范围：
--   1. 扩展 lp_quote_bom_status 的准备上下文字段。
--   2. 新增报价产品 BOM 准备记录。
--   3. 新增裸品参考成品包装选择主表 / 明细表。
--   4. 新增独立补录 BOM 版本主表 / 明细表。
--   5. 新增统一业务变更日志 lp_business_change_log。
--   6. 新增 lp_bom_costing_row 来源追溯关联表。
--
-- 约束：
--   - 不写回 U9。
--   - 不把补录数据写入 lp_bom_raw_hierarchy。
--   - 包装参考明细同时保存参考快照原值和技术员调整后值。
-- =============================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS v142_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v142_add_index_if_not_exists;

DELIMITER //

CREATE PROCEDURE v142_add_column_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_column_name VARCHAR(64),
  IN p_column_definition TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = p_table_name
      AND COLUMN_NAME = p_column_name
  ) THEN
    SET @v142_add_column_sql =
      CONCAT('ALTER TABLE ', p_table_name, ' ADD COLUMN ', p_column_definition);
    PREPARE v142_add_column_stmt FROM @v142_add_column_sql;
    EXECUTE v142_add_column_stmt;
    DEALLOCATE PREPARE v142_add_column_stmt;
  END IF;
END //

CREATE PROCEDURE v142_add_index_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_index_name VARCHAR(64),
  IN p_index_definition TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = p_table_name
      AND INDEX_NAME = p_index_name
  ) THEN
    SET @v142_add_index_sql =
      CONCAT('ALTER TABLE ', p_table_name, ' ', p_index_definition);
    PREPARE v142_add_index_stmt FROM @v142_add_index_sql;
    EXECUTE v142_add_index_stmt;
    DEALLOCATE PREPARE v142_add_index_stmt;
  END IF;
END //

DELIMITER ;

CALL v142_add_column_if_not_exists(
  'lp_quote_bom_status',
  'preparation_record_id',
  '`preparation_record_id` BIGINT DEFAULT NULL COMMENT ''报价产品 BOM 准备记录ID'' AFTER supplement_task_id');
CALL v142_add_column_if_not_exists(
  'lp_quote_bom_status',
  'product_type',
  '`product_type` VARCHAR(32) DEFAULT NULL COMMENT ''产品形态：BARE/NON_BARE/UNKNOWN'' AFTER product_code');
CALL v142_add_column_if_not_exists(
  'lp_quote_bom_status',
  'bare_product_code',
  '`bare_product_code` VARCHAR(64) DEFAULT NULL COMMENT ''裸品料号；裸品场景通常等于报价产品料号'' AFTER product_type');
CALL v142_add_column_if_not_exists(
  'lp_quote_bom_status',
  'need_package',
  '`need_package` TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''是否需要包装参考：1是0否'' AFTER bare_product_code');
CALL v142_add_column_if_not_exists(
  'lp_quote_bom_status',
  'reference_finished_code',
  '`reference_finished_code` VARCHAR(64) DEFAULT NULL COMMENT ''技术员选择的参考成品料号'' AFTER need_package');
CALL v142_add_column_if_not_exists(
  'lp_quote_bom_status',
  'source_top_product_code',
  '`source_top_product_code` VARCHAR(64) DEFAULT NULL COMMENT ''包装参考来源顶层成品料号'' AFTER reference_finished_code');
CALL v142_add_column_if_not_exists(
  'lp_quote_bom_status',
  'review_status',
  '`review_status` VARCHAR(32) NOT NULL DEFAULT ''NOT_SUBMITTED'' COMMENT ''财务审核状态预留'' AFTER bom_status');
CALL v142_add_column_if_not_exists(
  'lp_quote_bom_status',
  'reviewer_user_id',
  '`reviewer_user_id` BIGINT DEFAULT NULL COMMENT ''财务审核人ID'' AFTER technician_name');
CALL v142_add_column_if_not_exists(
  'lp_quote_bom_status',
  'reviewer_name',
  '`reviewer_name` VARCHAR(128) DEFAULT NULL COMMENT ''财务审核人姓名'' AFTER reviewer_user_id');
CALL v142_add_column_if_not_exists(
  'lp_quote_bom_status',
  'reviewed_at',
  '`reviewed_at` DATETIME DEFAULT NULL COMMENT ''财务审核时间'' AFTER reviewer_name');
CALL v142_add_column_if_not_exists(
  'lp_quote_bom_status',
  'costing_build_batch_id',
  '`costing_build_batch_id` VARCHAR(64) DEFAULT NULL COMMENT ''结算行生成批次ID'' AFTER sync_batch_id');

CALL v142_add_index_if_not_exists(
  'lp_quote_bom_status',
  'idx_quote_bom_status_preparation',
  'ADD INDEX idx_quote_bom_status_preparation (preparation_record_id)');
CALL v142_add_index_if_not_exists(
  'lp_quote_bom_status',
  'idx_quote_bom_status_product_type',
  'ADD INDEX idx_quote_bom_status_product_type (product_type, bom_status, review_status)');
CALL v142_add_index_if_not_exists(
  'lp_quote_bom_status',
  'idx_quote_bom_status_bare_product',
  'ADD INDEX idx_quote_bom_status_bare_product (bare_product_code)');
CALL v142_add_index_if_not_exists(
  'lp_quote_bom_status',
  'idx_quote_bom_status_reference_finished',
  'ADD INDEX idx_quote_bom_status_reference_finished (reference_finished_code, source_top_product_code)');
CALL v142_add_index_if_not_exists(
  'lp_quote_bom_status',
  'idx_quote_bom_status_costing_batch',
  'ADD INDEX idx_quote_bom_status_costing_batch (costing_build_batch_id)');

CREATE TABLE IF NOT EXISTS lp_quote_bom_preparation_record (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  quote_bom_status_id BIGINT DEFAULT NULL COMMENT 'lp_quote_bom_status.id',
  oa_form_id BIGINT NOT NULL COMMENT 'oa_form.id',
  oa_form_item_id BIGINT NOT NULL COMMENT 'oa_form_item.id',
  oa_no VARCHAR(64) NOT NULL COMMENT '报价单号',
  quote_product_code VARCHAR(64) NOT NULL COMMENT '报价产品料号',
  product_type VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN' COMMENT 'BARE/NON_BARE/UNKNOWN',
  bare_product_code VARCHAR(64) DEFAULT NULL COMMENT '裸品料号',
  need_package TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否需要包装参考',
  reference_finished_code VARCHAR(64) DEFAULT NULL COMMENT '技术员选择的参考成品料号',
  source_top_product_code VARCHAR(64) DEFAULT NULL COMMENT '包装结构快照来源顶层成品料号',
  cost_period_month VARCHAR(7) DEFAULT NULL COMMENT '成本年月 YYYY-MM',
  preparation_status VARCHAR(32) NOT NULL DEFAULT 'INIT' COMMENT 'INIT/READY/NEED_TECH/TECH_SUBMITTED/CONFIRMED/REJECTED',
  review_status VARCHAR(32) NOT NULL DEFAULT 'NOT_SUBMITTED' COMMENT 'NOT_SUBMITTED/PENDING/APPROVED/REJECTED',
  technician_user_id BIGINT DEFAULT NULL COMMENT '技术员用户ID',
  technician_name VARCHAR(128) DEFAULT NULL COMMENT '技术员姓名',
  task_id BIGINT DEFAULT NULL COMMENT 'lp_bom_supplement_task.id',
  reviewer_user_id BIGINT DEFAULT NULL COMMENT '财务审核人ID',
  reviewer_name VARCHAR(128) DEFAULT NULL COMMENT '财务审核人姓名',
  reviewed_at DATETIME DEFAULT NULL COMMENT '财务审核时间',
  costing_build_batch_id VARCHAR(64) DEFAULT NULL COMMENT '结算行生成批次ID',
  reused_from_task_id BIGINT DEFAULT NULL COMMENT '复用来源任务ID',
  reused_from_oa_no VARCHAR(64) DEFAULT NULL COMMENT '复用来源报价单号',
  reused_from_oa_form_item_id BIGINT DEFAULT NULL COMMENT '复用来源报价产品行ID',
  reuse_type VARCHAR(32) DEFAULT NULL COMMENT 'MANUAL_BOM/PACKAGE_REFERENCE',
  reuse_valid_until DATE DEFAULT NULL COMMENT '补录 BOM 复用有效期；包装参考可为空',
  active_flag TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否当前有效记录',
  error_message VARCHAR(1000) DEFAULT NULL COMMENT '异常说明',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_qbp_record_item_month (oa_form_item_id, cost_period_month),
  KEY idx_qbp_record_oa (oa_no, oa_form_item_id),
  KEY idx_qbp_record_product (quote_product_code, product_type, active_flag),
  KEY idx_qbp_record_bare (bare_product_code),
  KEY idx_qbp_record_task (task_id),
  KEY idx_qbp_record_reference (reference_finished_code, source_top_product_code),
  KEY idx_qbp_record_review (review_status, active_flag),
  KEY idx_qbp_record_reuse (reuse_type, reuse_valid_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='报价产品 BOM 准备记录表';

CREATE TABLE IF NOT EXISTS lp_quote_bom_package_reference (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  preparation_id BIGINT NOT NULL COMMENT 'lp_quote_bom_preparation_record.id',
  task_id BIGINT DEFAULT NULL COMMENT 'lp_bom_supplement_task.id',
  oa_no VARCHAR(64) NOT NULL COMMENT '报价单号',
  oa_form_item_id BIGINT NOT NULL COMMENT 'oa_form_item.id',
  quote_product_code VARCHAR(64) NOT NULL COMMENT '报价产品料号',
  bare_product_code VARCHAR(64) NOT NULL COMMENT '裸品料号',
  reference_finished_code VARCHAR(64) NOT NULL COMMENT '技术员选择的参考成品料号',
  source_top_product_code VARCHAR(64) NOT NULL COMMENT '包装结构快照来源顶层成品料号',
  period_month VARCHAR(7) NOT NULL COMMENT '期间 YYYY-MM',
  snapshot_id BIGINT DEFAULT NULL COMMENT 'lp_package_component_snapshot.id',
  reference_status VARCHAR(32) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/SUBMITTED/APPROVED/REJECTED/VOIDED',
  selected_line_count INT NOT NULL DEFAULT 0 COMMENT '已选择包装明细行数',
  edited_flag TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否存在技术员调整',
  active_flag TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否当前有效',
  reused_from_reference_id BIGINT DEFAULT NULL COMMENT '复用来源包装参考ID',
  remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_qbp_pkg_ref_prepare (preparation_id),
  KEY idx_qbp_pkg_ref_task (task_id),
  KEY idx_qbp_pkg_ref_oa (oa_no, oa_form_item_id),
  KEY idx_qbp_pkg_ref_bare (bare_product_code, active_flag),
  KEY idx_qbp_pkg_ref_finished (reference_finished_code, source_top_product_code, period_month),
  KEY idx_qbp_pkg_ref_snapshot (snapshot_id),
  KEY idx_qbp_pkg_ref_status (reference_status, active_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='裸品报价参考成品包装选择主表';

CREATE TABLE IF NOT EXISTS lp_quote_bom_package_reference_detail (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  package_reference_id BIGINT NOT NULL COMMENT 'lp_quote_bom_package_reference.id',
  preparation_id BIGINT NOT NULL COMMENT 'lp_quote_bom_preparation_record.id',
  task_id BIGINT DEFAULT NULL COMMENT 'lp_bom_supplement_task.id',
  oa_no VARCHAR(64) NOT NULL COMMENT '报价单号',
  oa_form_item_id BIGINT NOT NULL COMMENT 'oa_form_item.id',
  bare_product_code VARCHAR(64) NOT NULL COMMENT '裸品料号',
  reference_finished_code VARCHAR(64) NOT NULL COMMENT '技术员选择的参考成品料号',
  source_top_product_code VARCHAR(64) NOT NULL COMMENT '包装结构快照来源顶层成品料号',
  snapshot_id BIGINT DEFAULT NULL COMMENT 'lp_package_component_snapshot.id',
  snapshot_detail_id BIGINT DEFAULT NULL COMMENT 'lp_package_component_snapshot_detail.id',
  line_no INT NOT NULL COMMENT '包装参考明细行号',
  package_parent_code VARCHAR(64) NOT NULL COMMENT '包装组件父件料号',
  package_parent_name VARCHAR(180) DEFAULT NULL COMMENT '包装组件父件名称',
  package_parent_spec VARCHAR(255) DEFAULT NULL COMMENT '包装组件父件规格',
  package_parent_model VARCHAR(128) DEFAULT NULL COMMENT '包装组件父件型号',
  package_parent_drawing_no VARCHAR(128) DEFAULT NULL COMMENT '包装组件父件图号',
  package_parent_shape_attr VARCHAR(64) DEFAULT NULL COMMENT '包装组件父件形态属性',
  package_parent_main_category_code VARCHAR(64) DEFAULT NULL COMMENT '包装组件父件主分类',
  package_parent_unit VARCHAR(32) DEFAULT NULL COMMENT '包装组件父件单位',
  package_parent_code_in_reference_bom VARCHAR(64) DEFAULT NULL COMMENT '参考成品 BOM 中包装父件所在父料号',
  package_qty_per_parent DECIMAL(20,8) DEFAULT NULL COMMENT '参考快照：包装父件相对直接父件用量',
  package_qty_per_top DECIMAL(20,8) DEFAULT NULL COMMENT '参考快照：包装父件相对参考成品累计用量',
  package_parent_base_qty DECIMAL(20,8) DEFAULT NULL COMMENT '参考快照：包装父件所在 BOM 行母件底数',
  adjusted_package_qty_per_parent DECIMAL(20,8) DEFAULT NULL COMMENT '技术员调整：包装父件相对直接父件用量',
  adjusted_package_qty_per_top DECIMAL(20,8) DEFAULT NULL COMMENT '技术员调整：包装父件相对参考成品累计用量',
  adjusted_package_parent_base_qty DECIMAL(20,8) DEFAULT NULL COMMENT '技术员调整：包装父件母件底数',
  package_material_code VARCHAR(64) NOT NULL COMMENT '包装子件料号',
  package_material_name VARCHAR(180) DEFAULT NULL COMMENT '包装子件名称',
  package_material_spec VARCHAR(255) DEFAULT NULL COMMENT '包装子件规格',
  package_material_model VARCHAR(128) DEFAULT NULL COMMENT '包装子件型号',
  package_material_drawing_no VARCHAR(128) DEFAULT NULL COMMENT '包装子件图号',
  package_material_shape_attr VARCHAR(64) DEFAULT NULL COMMENT '包装子件形态属性',
  package_material_main_category_code VARCHAR(64) DEFAULT NULL COMMENT '包装子件主分类',
  package_material_unit VARCHAR(32) DEFAULT NULL COMMENT '包装子件单位',
  child_qty_per_parent DECIMAL(20,8) DEFAULT NULL COMMENT '参考快照：子件相对包装父件用量',
  child_qty_per_top DECIMAL(20,8) DEFAULT NULL COMMENT '参考快照：子件相对参考成品累计用量',
  child_parent_base_qty DECIMAL(20,8) DEFAULT NULL COMMENT '参考快照：子件所在 BOM 行母件底数',
  adjusted_child_qty_per_parent DECIMAL(20,8) DEFAULT NULL COMMENT '技术员调整：子件相对包装父件用量',
  adjusted_child_qty_per_top DECIMAL(20,8) DEFAULT NULL COMMENT '技术员调整：子件累计用量',
  adjusted_child_parent_base_qty DECIMAL(20,8) DEFAULT NULL COMMENT '技术员调整：子件母件底数',
  qty_per_top DECIMAL(20,8) DEFAULT NULL COMMENT '合成完整 BOM 时使用的最终累计用量',
  unit VARCHAR(32) DEFAULT NULL COMMENT '合成完整 BOM 使用单位',
  source_raw_hierarchy_id BIGINT DEFAULT NULL COMMENT '来源 lp_bom_raw_hierarchy.id',
  source_u9_bom_id BIGINT DEFAULT NULL COMMENT '来源 lp_bom_u9_source.id',
  source_parent_code VARCHAR(64) DEFAULT NULL COMMENT '来源父料号',
  source_path VARCHAR(1024) DEFAULT NULL COMMENT '来源 BOM path',
  selected_flag TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否被技术员选择带入',
  edited_flag TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否有字段调整',
  remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_qbp_pkg_ref_detail_line (package_reference_id, line_no),
  KEY idx_qbp_pkg_ref_detail_prepare (preparation_id),
  KEY idx_qbp_pkg_ref_detail_task (task_id),
  KEY idx_qbp_pkg_ref_detail_oa (oa_no, oa_form_item_id),
  KEY idx_qbp_pkg_ref_detail_source_top (reference_finished_code, source_top_product_code),
  KEY idx_qbp_pkg_ref_detail_parent (package_parent_code),
  KEY idx_qbp_pkg_ref_detail_child (package_material_code),
  KEY idx_qbp_pkg_ref_detail_snapshot (snapshot_id, snapshot_detail_id),
  KEY idx_qbp_pkg_ref_detail_selected (selected_flag, edited_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='裸品报价参考成品包装选择明细表';

CREATE TABLE IF NOT EXISTS lp_quote_bom_supplement_version (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  preparation_id BIGINT NOT NULL COMMENT 'lp_quote_bom_preparation_record.id',
  task_id BIGINT DEFAULT NULL COMMENT 'lp_bom_supplement_task.id',
  task_no VARCHAR(64) DEFAULT NULL COMMENT '补录任务号',
  oa_no VARCHAR(64) NOT NULL COMMENT '报价单号',
  oa_form_item_id BIGINT NOT NULL COMMENT 'oa_form_item.id',
  quote_product_code VARCHAR(64) NOT NULL COMMENT '报价产品料号',
  product_type VARCHAR(32) NOT NULL COMMENT 'BARE/NON_BARE/UNKNOWN',
  supplement_scope VARCHAR(32) NOT NULL COMMENT 'NON_BARE_FULL_BOM/BARE_BODY_BOM',
  bom_source VARCHAR(32) NOT NULL DEFAULT 'TECH_SUPPLEMENT' COMMENT 'TECH_SUPPLEMENT/REUSED_SUPPLEMENT',
  version_no INT NOT NULL DEFAULT 1 COMMENT '任务内版本号',
  version_status VARCHAR(32) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/SUBMITTED/APPROVED/REJECTED/VOIDED',
  active_flag TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否当前有效版本',
  period_month VARCHAR(7) DEFAULT NULL COMMENT '成本年月 YYYY-MM',
  effective_from DATE DEFAULT NULL COMMENT '补录版本生效日期',
  effective_to DATE DEFAULT NULL COMMENT '补录版本失效日期',
  reuse_valid_until DATE DEFAULT NULL COMMENT '补录 BOM 复用有效期',
  reused_from_version_id BIGINT DEFAULT NULL COMMENT '复用来源补录版本ID',
  submitted_by BIGINT DEFAULT NULL COMMENT '提交人ID',
  submitted_by_name VARCHAR(128) DEFAULT NULL COMMENT '提交人姓名',
  submitted_at DATETIME DEFAULT NULL COMMENT '提交时间',
  reviewer_user_id BIGINT DEFAULT NULL COMMENT '审核人ID',
  reviewer_name VARCHAR(128) DEFAULT NULL COMMENT '审核人姓名',
  reviewed_at DATETIME DEFAULT NULL COMMENT '审核时间',
  review_comment VARCHAR(1000) DEFAULT NULL COMMENT '审核意见',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_qbp_supp_version_task_scope (task_id, supplement_scope, version_no),
  KEY idx_qbp_supp_version_prepare (preparation_id),
  KEY idx_qbp_supp_version_oa (oa_no, oa_form_item_id),
  KEY idx_qbp_supp_version_product (quote_product_code, product_type, supplement_scope),
  KEY idx_qbp_supp_version_status (version_status, active_flag),
  KEY idx_qbp_supp_version_reuse (reuse_valid_until, active_flag),
  KEY idx_qbp_supp_version_reused_from (reused_from_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='报价产品独立补录 BOM 版本主表';

CREATE TABLE IF NOT EXISTS lp_quote_bom_supplement_detail (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  supplement_version_id BIGINT NOT NULL COMMENT 'lp_quote_bom_supplement_version.id',
  preparation_id BIGINT NOT NULL COMMENT 'lp_quote_bom_preparation_record.id',
  task_id BIGINT DEFAULT NULL COMMENT 'lp_bom_supplement_task.id',
  oa_no VARCHAR(64) NOT NULL COMMENT '报价单号',
  oa_form_item_id BIGINT NOT NULL COMMENT 'oa_form_item.id',
  quote_product_code VARCHAR(64) NOT NULL COMMENT '报价产品料号',
  supplement_scope VARCHAR(32) NOT NULL COMMENT 'NON_BARE_FULL_BOM/BARE_BODY_BOM',
  line_no INT NOT NULL COMMENT '补录行号',
  level INT NOT NULL DEFAULT 0 COMMENT '层级',
  parent_code VARCHAR(64) DEFAULT NULL COMMENT '父件料号',
  material_code VARCHAR(64) NOT NULL COMMENT '当前料号',
  material_name VARCHAR(180) DEFAULT NULL COMMENT '料品名称',
  material_spec VARCHAR(255) DEFAULT NULL COMMENT '料品规格',
  material_model VARCHAR(128) DEFAULT NULL COMMENT '型号',
  drawing_no VARCHAR(128) DEFAULT NULL COMMENT '图号',
  shape_attr VARCHAR(64) DEFAULT NULL COMMENT '形态属性',
  main_category_code VARCHAR(64) DEFAULT NULL COMMENT '主分类',
  source_category VARCHAR(64) DEFAULT NULL COMMENT '生产分类',
  cost_element_code VARCHAR(64) DEFAULT NULL COMMENT '成本要素编码',
  bom_purpose VARCHAR(64) DEFAULT NULL COMMENT 'BOM 用途',
  bom_version VARCHAR(128) DEFAULT NULL COMMENT 'BOM 版本',
  qty_per_parent DECIMAL(20,8) DEFAULT NULL COMMENT '相对直接父用量',
  qty_per_top DECIMAL(20,8) DEFAULT NULL COMMENT '累计到顶层用量',
  parent_base_qty DECIMAL(20,8) DEFAULT NULL COMMENT '母件底数',
  unit VARCHAR(32) DEFAULT NULL COMMENT '单位',
  path VARCHAR(1024) DEFAULT NULL COMMENT '补录层级路径',
  sort_seq INT DEFAULT NULL COMMENT '排序号',
  source_raw_hierarchy_id BIGINT DEFAULT NULL COMMENT '来源正式 BOM 节点ID，补录时一般为空',
  source_u9_bom_id BIGINT DEFAULT NULL COMMENT '来源 U9 BOM 行ID，补录时一般为空',
  manual_flag TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否人工补录',
  remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_qbp_supp_detail_line (supplement_version_id, line_no),
  KEY idx_qbp_supp_detail_prepare (preparation_id),
  KEY idx_qbp_supp_detail_task (task_id),
  KEY idx_qbp_supp_detail_oa (oa_no, oa_form_item_id),
  KEY idx_qbp_supp_detail_product (quote_product_code, supplement_scope),
  KEY idx_qbp_supp_detail_material (material_code),
  KEY idx_qbp_supp_detail_parent (parent_code),
  KEY idx_qbp_supp_detail_source (source_raw_hierarchy_id, source_u9_bom_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='报价产品独立补录 BOM 明细表';

CREATE TABLE IF NOT EXISTS lp_business_change_log (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  biz_domain VARCHAR(64) NOT NULL COMMENT '业务域，如 QUOTE_BOM_PREPARATION',
  biz_type VARCHAR(64) NOT NULL COMMENT '业务类型，如 PACKAGE_REFERENCE_DETAIL',
  biz_id BIGINT DEFAULT NULL COMMENT '被修改业务主表ID',
  biz_detail_id BIGINT DEFAULT NULL COMMENT '被修改明细ID',
  oa_no VARCHAR(64) DEFAULT NULL COMMENT '报价单号',
  oa_form_item_id BIGINT DEFAULT NULL COMMENT '报价产品行ID',
  task_id BIGINT DEFAULT NULL COMMENT '补录任务ID',
  field_name VARCHAR(128) NOT NULL COMMENT '字段名',
  field_label VARCHAR(128) DEFAULT NULL COMMENT '字段中文名',
  before_value TEXT DEFAULT NULL COMMENT '修改前值',
  after_value TEXT DEFAULT NULL COMMENT '修改后值',
  change_reason VARCHAR(500) DEFAULT NULL COMMENT '修改原因',
  changed_by BIGINT DEFAULT NULL COMMENT '修改人ID',
  changed_by_name VARCHAR(128) DEFAULT NULL COMMENT '修改人姓名',
  changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
  change_source VARCHAR(64) NOT NULL DEFAULT 'SYSTEM' COMMENT 'OA_COLLABORATION/FINANCE_REVIEW/SYSTEM',
  submit_batch_no VARCHAR(64) DEFAULT NULL COMMENT '同一次提交批次号',
  request_id VARCHAR(128) DEFAULT NULL COMMENT '请求ID',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_business_change_biz (biz_domain, biz_type, biz_id),
  KEY idx_business_change_detail (biz_detail_id),
  KEY idx_business_change_oa (oa_no, oa_form_item_id),
  KEY idx_business_change_task (task_id),
  KEY idx_business_change_field (field_name),
  KEY idx_business_change_batch (submit_batch_no),
  KEY idx_business_change_changed_at (changed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='统一业务变更日志表';

CREATE TABLE IF NOT EXISTS lp_bom_costing_row_source_ref (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  costing_row_id BIGINT NOT NULL COMMENT 'lp_bom_costing_row.id',
  oa_no VARCHAR(64) NOT NULL COMMENT '报价单号',
  oa_form_item_id BIGINT DEFAULT NULL COMMENT '报价产品行ID',
  quote_product_code VARCHAR(64) DEFAULT NULL COMMENT '报价产品料号',
  source_part_type VARCHAR(32) NOT NULL COMMENT 'RAW_PRODUCT_BOM/BARE_PRODUCT_BOM/REFERENCED_PACKAGE/MANUAL_SUPPLEMENT',
  source_raw_hierarchy_id BIGINT DEFAULT NULL COMMENT '来源正式 BOM 节点ID',
  source_task_id BIGINT DEFAULT NULL COMMENT '补录任务ID',
  preparation_id BIGINT DEFAULT NULL COMMENT 'BOM 准备记录ID',
  supplement_version_id BIGINT DEFAULT NULL COMMENT '补录 BOM 版本ID',
  supplement_detail_id BIGINT DEFAULT NULL COMMENT '补录 BOM 明细ID',
  package_reference_id BIGINT DEFAULT NULL COMMENT '包装参考主表ID',
  package_reference_detail_id BIGINT DEFAULT NULL COMMENT '包装参考明细ID',
  reference_finished_code VARCHAR(64) DEFAULT NULL COMMENT '参考成品料号',
  source_top_product_code VARCHAR(64) DEFAULT NULL COMMENT '包装参考来源顶层成品料号',
  source_snapshot_id BIGINT DEFAULT NULL COMMENT '包装结构快照ID',
  source_snapshot_detail_id BIGINT DEFAULT NULL COMMENT '包装结构快照明细ID',
  source_u9_bom_id BIGINT DEFAULT NULL COMMENT '来源 U9 BOM 行ID',
  source_path VARCHAR(1024) DEFAULT NULL COMMENT '来源路径',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_costing_source_row (costing_row_id),
  KEY idx_costing_source_oa (oa_no, oa_form_item_id),
  KEY idx_costing_source_product (quote_product_code, source_part_type),
  KEY idx_costing_source_raw (source_raw_hierarchy_id),
  KEY idx_costing_source_task (source_task_id, preparation_id),
  KEY idx_costing_source_supp (supplement_version_id, supplement_detail_id),
  KEY idx_costing_source_pkg (package_reference_id, package_reference_detail_id),
  KEY idx_costing_source_reference (reference_finished_code, source_top_product_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='BOM 结算行来源追溯关联表';

DROP PROCEDURE IF EXISTS v142_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v142_add_index_if_not_exists;

-- V142 结束
