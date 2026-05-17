-- =============================================================================
-- V64: CMS 料号年度成本来源生效模型
-- -----------------------------------------------------------------------------
-- 范围：
--   1. 新增 CMS 导入批次和三类原始来源表。
--   2. 新增料号年度成本来源生效表和刷新日志表。
--   3. 新增 CMS 辅料二级科目配置表，并初始化当前已确认的 CMS 辅助材料科目。
--
-- 约束：
--   - CMS 原始金额字段保留“分”和“元”两个口径，导入阶段只保存原始池。
--   - 导入批次只用于技术审计，不作为业务取数批次。
--   - 业务取数按 cost_year + parent_code + source_type + subject_code 的当前生效来源。
--   - 本迁移不改 lp_salary_cost / lp_aux_subject，不做导入即锁定。
-- =============================================================================

DROP PROCEDURE IF EXISTS _cms_cost_add_index_if_not_exists;
DROP PROCEDURE IF EXISTS _cms_cost_add_column_if_not_exists;

DELIMITER //

CREATE PROCEDURE _cms_cost_add_index_if_not_exists(
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
    SET @cms_cost_add_index_sql =
      CONCAT('ALTER TABLE ', p_table_name, ' ', p_index_definition);
    PREPARE cms_cost_add_index_stmt FROM @cms_cost_add_index_sql;
    EXECUTE cms_cost_add_index_stmt;
    DEALLOCATE PREPARE cms_cost_add_index_stmt;
  END IF;
END //

CREATE PROCEDURE _cms_cost_add_column_if_not_exists(
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
    SET @cms_cost_add_column_sql =
      CONCAT('ALTER TABLE ', p_table_name, ' ', p_column_definition);
    PREPARE cms_cost_add_column_stmt FROM @cms_cost_add_column_sql;
    EXECUTE cms_cost_add_column_stmt;
    DEALLOCATE PREPARE cms_cost_add_column_stmt;
  END IF;
END //

DELIMITER ;

CREATE TABLE IF NOT EXISTS cms_cost_import_batch (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  batch_no VARCHAR(64) NOT NULL COMMENT '技术导入记录号',
  import_type VARCHAR(40) NOT NULL DEFAULT 'EXCEL' COMMENT '导入方式：EXCEL=Excel导入，API=CMS接口同步',
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '导入状态：PENDING=待处理，IMPORTED=原始数据已导入，FAILED=失败',
  plan_file_name VARCHAR(255) DEFAULT NULL COMMENT '产品计划成本汇总文件名',
  workshop_file_name VARCHAR(255) DEFAULT NULL COMMENT '产品车间料工费汇总文件名',
  subject_file_name VARCHAR(255) DEFAULT NULL COMMENT '产品科目成本汇总文件名',
  plan_row_count INT NOT NULL DEFAULT 0 COMMENT '计划成本原始行数',
  workshop_row_count INT NOT NULL DEFAULT 0 COMMENT '车间料工费原始行数',
  subject_row_count INT NOT NULL DEFAULT 0 COMMENT '科目成本原始行数',
  salary_insert_count INT NOT NULL DEFAULT 0 COMMENT '废弃字段：不再导入即生成工资来源',
  salary_skip_count INT NOT NULL DEFAULT 0 COMMENT '废弃字段：不再导入即跳过工资来源',
  salary_blocked_count INT NOT NULL DEFAULT 0 COMMENT '废弃字段：工时阻断改在公共生效来源维护时记录',
  aux_insert_count INT NOT NULL DEFAULT 0 COMMENT '废弃字段：不再导入即生成辅料来源',
  aux_skip_count INT NOT NULL DEFAULT 0 COMMENT '废弃字段：不再导入即跳过辅料来源',
  error_count INT NOT NULL DEFAULT 0 COMMENT '错误数量',
  error_message TEXT DEFAULT NULL COMMENT '导入级错误信息',
  imported_by VARCHAR(64) DEFAULT NULL COMMENT '导入人',
  business_unit_type VARCHAR(32) NOT NULL DEFAULT '' COMMENT '业务单元类型：COMMERCIAL=商用，HOUSEHOLD=家用',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_cms_cost_batch_no (batch_no),
  KEY idx_cms_cost_batch_status (status),
  KEY idx_cms_cost_batch_but (business_unit_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='CMS成本数据技术导入记录';

CREATE TABLE IF NOT EXISTS cms_plan_cost_raw (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  import_batch_id BIGINT NOT NULL COMMENT '技术导入记录ID，关联cms_cost_import_batch.id',
  row_no INT NOT NULL COMMENT 'Excel行号',
  first_unit_code VARCHAR(32) DEFAULT NULL COMMENT '一级编码',
  first_unit_name VARCHAR(120) DEFAULT NULL COMMENT '一级编码名称',
  parent_code VARCHAR(80) NOT NULL COMMENT '父件编码',
  parent_name VARCHAR(180) DEFAULT NULL COMMENT '父件名称',
  parent_spec VARCHAR(180) DEFAULT NULL COMMENT '父件规格',
  parent_type VARCHAR(180) DEFAULT NULL COMMENT '父件型号',
  unit VARCHAR(32) DEFAULT NULL COMMENT '单位',
  working_hours DECIMAL(18,6) DEFAULT NULL COMMENT '工时',
  effective_date DATE DEFAULT NULL COMMENT '生效时间',
  effective_period VARCHAR(7) NOT NULL COMMENT '生效期间 yyyy-MM，由生效时间转换，用于匹配CMS来源期间',
  main_material_cost DECIMAL(18,6) DEFAULT NULL COMMENT '主材成本',
  aux_material_cost DECIMAL(18,6) DEFAULT NULL COMMENT '辅材成本',
  salary_cost DECIMAL(18,6) DEFAULT NULL COMMENT '工资成本',
  fund_cost DECIMAL(18,6) DEFAULT NULL COMMENT '经费成本',
  loss_cost DECIMAL(18,6) DEFAULT NULL COMMENT '损失成本',
  total_plan_cost DECIMAL(18,6) DEFAULT NULL COMMENT '计划价(总)',
  business_status VARCHAR(64) DEFAULT NULL COMMENT '业务状态',
  unapproved_items VARCHAR(255) DEFAULT NULL COMMENT '未审批项',
  description TEXT DEFAULT NULL COMMENT '制定说明',
  oa_no VARCHAR(255) DEFAULT NULL COMMENT 'OA单号',
  business_unit_type VARCHAR(32) NOT NULL DEFAULT '' COMMENT '业务单元类型：COMMERCIAL=商用，HOUSEHOLD=家用',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_cms_plan_batch (import_batch_id),
  KEY idx_cms_plan_parent (parent_code),
  KEY idx_cms_plan_effective (effective_date),
  KEY idx_cms_plan_parent_period (parent_code, effective_period),
  KEY idx_cms_plan_but (business_unit_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='CMS产品计划成本汇总原始数据';

CREATE TABLE IF NOT EXISTS cms_workshop_labor_raw (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  import_batch_id BIGINT NOT NULL COMMENT '技术导入记录ID，关联cms_cost_import_batch.id',
  row_no INT NOT NULL COMMENT 'Excel行号',
  period VARCHAR(7) NOT NULL COMMENT '期间 yyyy-MM',
  first_unit_code VARCHAR(32) DEFAULT NULL COMMENT '一级生产单元编码',
  first_unit_name VARCHAR(120) DEFAULT NULL COMMENT '一级生产单元名称',
  parent_code VARCHAR(80) NOT NULL COMMENT '父件编码/成品料号',
  parent_name VARCHAR(180) DEFAULT NULL COMMENT '父件名称',
  parent_spec VARCHAR(180) DEFAULT NULL COMMENT '父件规格',
  parent_type VARCHAR(180) DEFAULT NULL COMMENT '父件型号',
  last_unit_name VARCHAR(180) DEFAULT NULL COMMENT '末级生产单元名称',
  last_unit_code VARCHAR(80) DEFAULT NULL COMMENT '末级生产单元编码',
  working_hours DECIMAL(18,6) DEFAULT NULL COMMENT '工时',
  funding DECIMAL(18,6) DEFAULT NULL COMMENT '经费',
  working_cost_cent DECIMAL(18,6) DEFAULT NULL COMMENT '工资，原始单位：分',
  working_cost_yuan DECIMAL(18,6) DEFAULT NULL COMMENT '工资，转换后单位：元',
  build_flag VARCHAR(64) DEFAULT NULL COMMENT '构建标记',
  path TEXT DEFAULT NULL COMMENT '核算路径',
  source_row_id VARCHAR(80) NOT NULL COMMENT 'CMS原始id',
  sequence_no VARCHAR(120) DEFAULT NULL COMMENT '单据号',
  sequence_status VARCHAR(64) DEFAULT NULL COMMENT '单据状态',
  material_price DECIMAL(18,6) DEFAULT NULL COMMENT '材料计价，CMS原始单位：分',
  first_subject_code VARCHAR(64) DEFAULT NULL COMMENT '一级科目编码',
  first_subject_name VARCHAR(120) DEFAULT NULL COMMENT '一级科目名称',
  second_subject_code VARCHAR(64) DEFAULT NULL COMMENT '二级科目编码',
  second_subject_name VARCHAR(120) DEFAULT NULL COMMENT '二级科目名称',
  third_subject_code VARCHAR(64) DEFAULT NULL COMMENT '三级科目编码',
  third_subject_name VARCHAR(120) DEFAULT NULL COMMENT '三级科目名称',
  business_unit_type VARCHAR(32) NOT NULL DEFAULT '' COMMENT '业务单元类型：COMMERCIAL=商用，HOUSEHOLD=家用',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_cms_workshop_batch (import_batch_id),
  KEY idx_cms_workshop_parent_period (parent_code, period),
  KEY idx_cms_workshop_but (business_unit_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='CMS产品车间料工费汇总原始数据';

CREATE TABLE IF NOT EXISTS cms_product_subject_cost_raw (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  import_batch_id BIGINT NOT NULL COMMENT '技术导入记录ID，关联cms_cost_import_batch.id',
  row_no INT NOT NULL COMMENT 'Excel行号',
  period VARCHAR(7) NOT NULL COMMENT '期间 yyyy-MM',
  first_unit_code VARCHAR(32) DEFAULT NULL COMMENT '一级生产单元编码',
  first_unit_name VARCHAR(120) DEFAULT NULL COMMENT '一级生产单元名称',
  parent_code VARCHAR(80) NOT NULL COMMENT '父件编码/成品料号',
  parent_name VARCHAR(180) DEFAULT NULL COMMENT '父件名称',
  parent_spec VARCHAR(180) DEFAULT NULL COMMENT '父件规格',
  parent_type VARCHAR(180) DEFAULT NULL COMMENT '父件型号',
  last_subject_code VARCHAR(64) DEFAULT NULL COMMENT '末级科目编码',
  last_subject_name VARCHAR(120) DEFAULT NULL COMMENT '末级科目名称',
  last_subject_level VARCHAR(32) DEFAULT NULL COMMENT '末级科目层级',
  material_price DECIMAL(18,6) DEFAULT NULL COMMENT '材料计价，CMS原始单位：分',
  material_price_yuan DECIMAL(18,6) DEFAULT NULL COMMENT '材料计价，转换后单位：元',
  build_flag VARCHAR(64) DEFAULT NULL COMMENT '构建标记',
  path TEXT DEFAULT NULL COMMENT '核算路径',
  first_subject_code VARCHAR(64) DEFAULT NULL COMMENT '一级科目编码',
  first_subject_name VARCHAR(120) DEFAULT NULL COMMENT '一级科目名称',
  second_subject_code VARCHAR(64) DEFAULT NULL COMMENT '二级科目编码',
  second_subject_name VARCHAR(120) DEFAULT NULL COMMENT '二级科目名称',
  third_subject_code VARCHAR(64) DEFAULT NULL COMMENT '三级科目编码',
  third_subject_name VARCHAR(120) DEFAULT NULL COMMENT '三级科目名称',
  source_row_id VARCHAR(80) NOT NULL COMMENT 'CMS原始id',
  sequence_no VARCHAR(120) DEFAULT NULL COMMENT '单据号',
  sequence_status VARCHAR(64) DEFAULT NULL COMMENT '单据状态',
  business_unit_type VARCHAR(32) NOT NULL DEFAULT '' COMMENT '业务单元类型：COMMERCIAL=商用，HOUSEHOLD=家用',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_cms_subject_batch (import_batch_id),
  KEY idx_cms_subject_parent_period (parent_code, period),
  KEY idx_cms_subject_subject (first_subject_name, second_subject_name, third_subject_name),
  KEY idx_cms_subject_but (business_unit_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='CMS产品科目成本汇总原始数据';

-- 已建库补齐 raw 去重依赖字段的非空约束，避免 MySQL UNIQUE KEY 对 NULL 放行。
CALL _cms_cost_add_column_if_not_exists(
  'cms_plan_cost_raw',
  'effective_period',
  'ADD COLUMN effective_period VARCHAR(7) NULL COMMENT ''生效期间 yyyy-MM，由生效时间转换，用于匹配CMS来源期间'' AFTER effective_date'
);

UPDATE cms_plan_cost_raw
SET effective_period = DATE_FORMAT(effective_date, '%Y-%m')
WHERE (effective_period IS NULL OR effective_period = '')
  AND effective_date IS NOT NULL;

UPDATE cms_plan_cost_raw SET business_unit_type = '' WHERE business_unit_type IS NULL;
UPDATE cms_workshop_labor_raw SET business_unit_type = '' WHERE business_unit_type IS NULL;
UPDATE cms_product_subject_cost_raw SET business_unit_type = '' WHERE business_unit_type IS NULL;

ALTER TABLE cms_plan_cost_raw
  MODIFY effective_period VARCHAR(7) NOT NULL COMMENT '生效期间 yyyy-MM，由生效时间转换，用于匹配CMS来源期间';

ALTER TABLE cms_plan_cost_raw
  MODIFY business_unit_type VARCHAR(32) NOT NULL DEFAULT '' COMMENT '业务单元类型：COMMERCIAL=商用，HOUSEHOLD=家用';

ALTER TABLE cms_workshop_labor_raw
  MODIFY source_row_id VARCHAR(80) NOT NULL COMMENT 'CMS原始id';

ALTER TABLE cms_workshop_labor_raw
  MODIFY business_unit_type VARCHAR(32) NOT NULL DEFAULT '' COMMENT '业务单元类型：COMMERCIAL=商用，HOUSEHOLD=家用';

ALTER TABLE cms_product_subject_cost_raw
  MODIFY source_row_id VARCHAR(80) NOT NULL COMMENT 'CMS原始id';

ALTER TABLE cms_product_subject_cost_raw
  MODIFY business_unit_type VARCHAR(32) NOT NULL DEFAULT '' COMMENT '业务单元类型：COMMERCIAL=商用，HOUSEHOLD=家用';

-- CMS 原始池以 CMS 业务原始行为准：重复 Excel/API/ETL 入库时更新当前行，不重复累加。
-- 计划成本导出没有 CMS id，用业务单元 + 父件 + 生效期间识别当前计划成本。
CALL _cms_cost_add_index_if_not_exists(
  'cms_plan_cost_raw',
  'uk_cms_plan_current',
  'ADD UNIQUE KEY uk_cms_plan_current (business_unit_type, parent_code, effective_period)'
);

-- 车间料工费、科目成本的 source_row_id 来自 CMS 导出 Excel 的 id 字段。
CALL _cms_cost_add_index_if_not_exists(
  'cms_workshop_labor_raw',
  'uk_cms_workshop_source_row',
  'ADD UNIQUE KEY uk_cms_workshop_source_row (business_unit_type, period, source_row_id)'
);

CALL _cms_cost_add_index_if_not_exists(
  'cms_product_subject_cost_raw',
  'uk_cms_subject_source_row',
  'ADD UNIQUE KEY uk_cms_subject_source_row (business_unit_type, period, source_row_id)'
);

CREATE TABLE IF NOT EXISTS cms_cost_source_effective (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  cost_year INT NOT NULL COMMENT '成本年度，例如2026',
  source_type VARCHAR(40) NOT NULL COMMENT '来源类型：SALARY_DIRECT=直接人工，SALARY_INDIRECT=辅助员工工资，AUX_SUBJECT=辅料科目',
  parent_code VARCHAR(80) NOT NULL COMMENT '父产品编码/成品料号',
  period VARCHAR(7) NOT NULL COMMENT '当前生效的CMS来源期间 yyyy-MM',
  subject_code VARCHAR(64) NOT NULL DEFAULT '' COMMENT 'CMS二级科目编码；直接人工工资固定0301，辅助人员工资取CMS科目原表，辅料公共生效来源取CMS二级科目',
  subject_name VARCHAR(120) DEFAULT NULL COMMENT 'CMS二级科目名称，辅料公共生效来源使用',
  source_table VARCHAR(80) NOT NULL COMMENT '来源原始表名',
  source_row_ids TEXT NOT NULL COMMENT '当前生效来源关联的CMS原始行ID列表，逗号分隔或JSON数组',
  amount_yuan DECIMAL(18,6) NOT NULL COMMENT '当前生效来源汇总金额，单位：元',
  default_flag TINYINT NOT NULL DEFAULT 1 COMMENT '是否年度默认来源：1=默认来源，0=人工刷新来源',
  refresh_reason VARCHAR(500) DEFAULT NULL COMMENT '刷新原因，例如一月CMS数据异常改用四月',
  confirmed_by VARCHAR(64) DEFAULT NULL COMMENT '确认人',
  confirmed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '确认时间',
  business_unit_type VARCHAR(32) NOT NULL DEFAULT '' COMMENT '业务单元类型：COMMERCIAL=商用，HOUSEHOLD=家用',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_cms_effective_source (cost_year, source_type, parent_code, subject_code, business_unit_type),
  KEY idx_cms_effective_parent (cost_year, parent_code),
  KEY idx_cms_effective_period (period),
  KEY idx_cms_effective_but (business_unit_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='CMS料号年度成本来源生效表';

CREATE TABLE IF NOT EXISTS cms_cost_source_effective_log (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  effective_source_id BIGINT DEFAULT NULL COMMENT '公共生效来源ID，关联cms_cost_source_effective.id',
  cost_year INT NOT NULL COMMENT '成本年度，例如2026',
  source_type VARCHAR(40) NOT NULL COMMENT '来源类型：SALARY_DIRECT=直接人工，SALARY_INDIRECT=辅助员工工资，AUX_SUBJECT=辅料科目',
  parent_code VARCHAR(80) NOT NULL COMMENT '父产品编码/成品料号',
  old_period VARCHAR(7) DEFAULT NULL COMMENT '刷新前来源期间 yyyy-MM',
  new_period VARCHAR(7) DEFAULT NULL COMMENT '刷新后来源期间 yyyy-MM',
  subject_code VARCHAR(64) NOT NULL DEFAULT '' COMMENT 'CMS二级科目编码；工资来源按生效来源展示口径记录',
  subject_name VARCHAR(120) DEFAULT NULL COMMENT 'CMS二级科目名称，工资为空',
  old_amount_yuan DECIMAL(18,6) DEFAULT NULL COMMENT '刷新前金额，单位：元',
  new_amount_yuan DECIMAL(18,6) DEFAULT NULL COMMENT '刷新后金额，单位：元',
  action_type VARCHAR(32) NOT NULL COMMENT '操作类型：DEFAULT=默认生成，REFRESH=人工刷新，CANCEL=取消生效，BLOCKED=资格阻断',
  message VARCHAR(500) DEFAULT NULL COMMENT '操作说明或阻断原因',
  operator VARCHAR(64) DEFAULT NULL COMMENT '操作人',
  business_unit_type VARCHAR(32) NOT NULL DEFAULT '' COMMENT '业务单元类型：COMMERCIAL=商用，HOUSEHOLD=家用',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_cms_effective_log_source (effective_source_id),
  KEY idx_cms_effective_log_parent (cost_year, parent_code),
  KEY idx_cms_effective_log_action (action_type),
  KEY idx_cms_effective_log_but (business_unit_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='CMS料号年度成本来源刷新日志';

DROP PROCEDURE IF EXISTS _cms_cost_add_index_if_not_exists;
DROP PROCEDURE IF EXISTS _cms_cost_add_column_if_not_exists;
