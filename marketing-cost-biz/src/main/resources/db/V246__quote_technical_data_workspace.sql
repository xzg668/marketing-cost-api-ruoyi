-- =============================================================================
-- V246: 报价技术资料补录工作台持久化模型
-- -----------------------------------------------------------------------------
-- 边界：
--   1. 只新增本迁移定义的 8 张表，不改动、不删除旧协作表。
--   2. 包装、辅料、工资明细独立分表，数量/工时/单价/金额统一 DECIMAL(20,8)。
--   3. 活动任务和活动产品使用可空活动锁唯一键，历史行可并存。
--   4. 已提交/已审批版本的明细不可变由服务校验与 Mapper 条件 SQL 双重保护。
-- =============================================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `lp_quote_tech_task` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `task_no` VARCHAR(64) NOT NULL COMMENT '技术资料任务号',
  `oa_form_id` BIGINT NOT NULL COMMENT 'OA报价单ID',
  `oa_no` VARCHAR(64) NOT NULL COMMENT 'OA报价单号',
  `accounting_month` CHAR(7) NOT NULL COMMENT '核算月份YYYY-MM',
  `business_unit_type` VARCHAR(32) NOT NULL COMMENT '业务单元',
  `applicable_org_code` VARCHAR(64) NOT NULL COMMENT '适用组织编码',
  `assignee_user_id` BIGINT NOT NULL COMMENT '当前技术负责人ID',
  `assignee_name` VARCHAR(128) NOT NULL COMMENT '当前技术负责人',
  `reviewer_user_id` BIGINT DEFAULT NULL COMMENT '当前审核人ID',
  `reviewer_name` VARCHAR(128) DEFAULT NULL COMMENT '当前审核人',
  `task_status` VARCHAR(32) NOT NULL DEFAULT 'PENDING'
    COMMENT 'PENDING/IN_PROGRESS/SUBMITTED/PARTIALLY_RETURNED/APPROVED/CANCELLED',
  `task_version` INT NOT NULL DEFAULT 0 COMMENT '任务乐观锁版本',
  `review_round` INT NOT NULL DEFAULT 0 COMMENT '当前审核轮次',
  `review_status` VARCHAR(32) NOT NULL DEFAULT 'NOT_STARTED'
    COMMENT 'NOT_STARTED/PENDING/PARTIALLY_RETURNED/PASSED',
  `source_system` VARCHAR(64) DEFAULT NULL COMMENT '任务来源系统',
  `source_request_id` VARCHAR(128) DEFAULT NULL COMMENT '来源请求标识',
  `external_system` VARCHAR(64) DEFAULT NULL COMMENT '协同外部系统',
  `external_task_id` VARCHAR(128) DEFAULT NULL COMMENT '外部待办ID',
  `external_task_status` VARCHAR(32) DEFAULT NULL COMMENT '外部待办状态',
  `external_last_sync_at` DATETIME DEFAULT NULL COMMENT '外部系统最近同步时间',
  `external_last_error` VARCHAR(1000) DEFAULT NULL COMMENT '外部系统最近错误',
  `due_at` DATETIME DEFAULT NULL COMMENT '任务截止时间',
  `submitted_at` DATETIME DEFAULT NULL COMMENT '当前轮次提交时间',
  `approved_at` DATETIME DEFAULT NULL COMMENT '审核通过时间',
  `cancelled_at` DATETIME DEFAULT NULL COMMENT '取消时间',
  `active_flag` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '1活动/0历史',
  `active_lock_key` VARCHAR(255) DEFAULT NULL COMMENT '活动唯一锁：OA+月份+负责人',
  `created_by` BIGINT DEFAULT NULL COMMENT '创建人ID',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_by` BIGINT DEFAULT NULL COMMENT '更新人ID',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_quote_tech_task_no` (`task_no`),
  UNIQUE KEY `uk_quote_tech_task_active_lock` (`active_lock_key`),
  KEY `idx_quote_tech_task_assignee` (`assignee_user_id`, `task_status`, `accounting_month`),
  KEY `idx_quote_tech_task_reviewer` (`reviewer_user_id`, `review_status`, `accounting_month`),
  KEY `idx_quote_tech_task_oa` (`oa_no`, `accounting_month`),
  KEY `idx_quote_tech_task_external` (`external_system`, `external_task_id`),
  CONSTRAINT `ck_quote_tech_task_month` CHECK (
    `accounting_month` REGEXP '^[0-9]{4}-(0[1-9]|1[0-2])$'),
  CONSTRAINT `ck_quote_tech_task_version` CHECK (`task_version` >= 0 AND `review_round` >= 0),
  CONSTRAINT `ck_quote_tech_task_status` CHECK (`task_status` IN (
    'PENDING','IN_PROGRESS','SUBMITTED','PARTIALLY_RETURNED','APPROVED','CANCELLED')),
  CONSTRAINT `ck_quote_tech_task_review_status` CHECK (`review_status` IN (
    'NOT_STARTED','PENDING','PARTIALLY_RETURNED','PASSED')),
  CONSTRAINT `ck_quote_tech_task_active_lock` CHECK (
    (`active_flag` = 1 AND `active_lock_key` = CONCAT(
      'OA:', `oa_no`, ':MONTH:', `accounting_month`, ':ASSIGNEE:', `assignee_user_id`))
    OR (`active_flag` = 0 AND `active_lock_key` IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='报价单级技术资料补录任务';

CREATE TABLE IF NOT EXISTS `lp_quote_tech_product` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `task_id` BIGINT NOT NULL COMMENT '技术资料任务ID',
  `oa_form_item_id` BIGINT NOT NULL COMMENT 'OA产品行ID',
  `level_no` INT NOT NULL DEFAULT 1 COMMENT '产品层级',
  `material_no` VARCHAR(64) DEFAULT NULL COMMENT '产品料号',
  `product_name` VARCHAR(255) DEFAULT NULL COMMENT '产品名称',
  `source_model` VARCHAR(255) DEFAULT NULL COMMENT 'OA来源产品型号',
  `source_spec` VARCHAR(255) DEFAULT NULL COMMENT 'OA来源产品规格',
  `quote_no` VARCHAR(64) NOT NULL COMMENT '报价单号快照',
  `accounting_month` CHAR(7) NOT NULL COMMENT '核算月份YYYY-MM',
  `source_snapshot_json` JSON NOT NULL COMMENT 'OA带入字段快照',
  `source_fingerprint` CHAR(64) NOT NULL COMMENT 'OA带入字段指纹',
  `product_status` VARCHAR(32) NOT NULL DEFAULT 'PENDING'
    COMMENT 'PENDING/EDITING/READY/SUBMITTED/RETURNED/APPROVED',
  `current_edit_version_id` BIGINT DEFAULT NULL COMMENT '当前编辑版本ID',
  `latest_submitted_version_id` BIGINT DEFAULT NULL COMMENT '最近提交版本ID',
  `effective_version_id` BIGINT DEFAULT NULL COMMENT '当前有效APPROVED版本ID',
  `effective_review_round` INT DEFAULT NULL COMMENT '有效版本审核轮次',
  `effective_at` DATETIME DEFAULT NULL COMMENT '有效时间',
  `active_flag` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '1活动/0历史',
  `active_lock_key` VARCHAR(160) DEFAULT NULL COMMENT '活动唯一锁：OA产品行+月份',
  `row_version` INT NOT NULL DEFAULT 0 COMMENT '产品行乐观锁',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_quote_tech_product_active_lock` (`active_lock_key`),
  KEY `idx_quote_tech_product_task` (`task_id`, `product_status`, `level_no`),
  KEY `idx_quote_tech_product_item_month` (`oa_form_item_id`, `accounting_month`),
  KEY `idx_quote_tech_product_effective` (`effective_version_id`),
  CONSTRAINT `fk_quote_tech_product_task` FOREIGN KEY (`task_id`)
    REFERENCES `lp_quote_tech_task` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_quote_tech_product_month` CHECK (
    `accounting_month` REGEXP '^[0-9]{4}-(0[1-9]|1[0-2])$'),
  CONSTRAINT `ck_quote_tech_product_level` CHECK (`level_no` > 0),
  CONSTRAINT `ck_quote_tech_product_status` CHECK (`product_status` IN (
    'PENDING','EDITING','READY','SUBMITTED','RETURNED','APPROVED')),
  CONSTRAINT `ck_quote_tech_product_row_version` CHECK (`row_version` >= 0),
  CONSTRAINT `ck_quote_tech_product_active_lock` CHECK (
    (`active_flag` = 1 AND `active_lock_key` = CONCAT(
      'ITEM:', `oa_form_item_id`, ':MONTH:', `accounting_month`))
    OR (`active_flag` = 0 AND `active_lock_key` IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='技术资料工作台产品行';

CREATE TABLE IF NOT EXISTS `lp_quote_tech_module` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `product_id` BIGINT NOT NULL COMMENT '技术产品ID',
  `module_type` VARCHAR(32) NOT NULL COMMENT 'PROFILE/PACKAGE/AUXILIARY/SALARY',
  `required_flag` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '1必填/0不需要',
  `requirement_reason_code` VARCHAR(64) NOT NULL COMMENT '必填判定原因码',
  `requirement_reason` VARCHAR(500) NOT NULL COMMENT '必填判定原因',
  `entry_mode` VARCHAR(32) NOT NULL DEFAULT 'NONE' COMMENT 'NONE/REFERENCE/MANUAL',
  `module_status` VARCHAR(32) NOT NULL DEFAULT 'PENDING'
    COMMENT 'NOT_REQUIRED/PENDING/EDITING/READY/SUBMITTED/RETURNED/APPROVED',
  `current_version_id` BIGINT DEFAULT NULL COMMENT '当前模块版本ID',
  `reference_source_type` VARCHAR(64) DEFAULT NULL COMMENT '参照来源类型',
  `reference_source_id` VARCHAR(128) DEFAULT NULL COMMENT '参照来源ID',
  `reference_source_version` VARCHAR(128) DEFAULT NULL COMMENT '参照来源版本',
  `reference_fingerprint` CHAR(64) DEFAULT NULL COMMENT '参照内容指纹',
  `reference_snapshot_json` JSON DEFAULT NULL COMMENT '引用时来源快照',
  `referenced_at` DATETIME DEFAULT NULL COMMENT '参照时间',
  `last_validation_code` VARCHAR(64) DEFAULT NULL COMMENT '最近校验码',
  `last_validation_message` VARCHAR(1000) DEFAULT NULL COMMENT '最近校验结果',
  `row_version` INT NOT NULL DEFAULT 0 COMMENT '模块乐观锁',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_quote_tech_module_product_type` (`product_id`, `module_type`),
  KEY `idx_quote_tech_module_status` (`module_type`, `module_status`),
  KEY `idx_quote_tech_module_current_version` (`current_version_id`),
  CONSTRAINT `fk_quote_tech_module_product` FOREIGN KEY (`product_id`)
    REFERENCES `lp_quote_tech_product` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_quote_tech_module_type` CHECK (`module_type` IN (
    'PROFILE','PACKAGE','AUXILIARY','SALARY')),
  CONSTRAINT `ck_quote_tech_module_entry_mode` CHECK (`entry_mode` IN (
    'NONE','REFERENCE','MANUAL')),
  CONSTRAINT `ck_quote_tech_module_status` CHECK (`module_status` IN (
    'NOT_REQUIRED','PENDING','EDITING','READY','SUBMITTED','RETURNED','APPROVED')),
  CONSTRAINT `ck_quote_tech_module_required` CHECK (
    (`required_flag` = 0 AND `module_status` = 'NOT_REQUIRED') OR `required_flag` = 1),
  CONSTRAINT `ck_quote_tech_module_row_version` CHECK (`row_version` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='技术产品的基本信息、包装、辅料和工资模块';

CREATE TABLE IF NOT EXISTS `lp_quote_tech_data_version` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `product_id` BIGINT NOT NULL COMMENT '技术产品ID',
  `version_no` INT NOT NULL COMMENT '产品内版本号',
  `version_status` VARCHAR(32) NOT NULL DEFAULT 'DRAFT'
    COMMENT 'DRAFT/SUBMITTED/RETURNED/APPROVED/VOIDED',
  `product_model` VARCHAR(255) DEFAULT NULL COMMENT '技术确认产品型号',
  `product_property` VARCHAR(128) DEFAULT NULL COMMENT '产品属性',
  `new_product_flag` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1新品/0非新品',
  `package_total_amount` DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '包装金额合计',
  `auxiliary_total_amount` DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '辅料金额合计',
  `salary_total_amount` DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '工资金额合计',
  `content_fingerprint` CHAR(64) DEFAULT NULL COMMENT '提交内容指纹',
  `reference_snapshot_json` JSON DEFAULT NULL COMMENT '各模块参照快照',
  `created_from_version_id` BIGINT DEFAULT NULL COMMENT '退回后复制来源版本ID',
  `submitted_by` BIGINT DEFAULT NULL COMMENT '提交人ID',
  `submitted_at` DATETIME DEFAULT NULL COMMENT '提交时间',
  `approved_by` BIGINT DEFAULT NULL COMMENT '审批人ID',
  `approved_at` DATETIME DEFAULT NULL COMMENT '审批时间',
  `row_version` INT NOT NULL DEFAULT 0 COMMENT '草稿乐观锁',
  `created_by` BIGINT DEFAULT NULL COMMENT '创建人ID',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_by` BIGINT DEFAULT NULL COMMENT '更新人ID',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_quote_tech_version_product_no` (`product_id`, `version_no`),
  KEY `idx_quote_tech_version_status` (`product_id`, `version_status`, `version_no`),
  KEY `idx_quote_tech_version_fingerprint` (`content_fingerprint`),
  KEY `idx_quote_tech_version_created_from` (`created_from_version_id`),
  CONSTRAINT `fk_quote_tech_version_product` FOREIGN KEY (`product_id`)
    REFERENCES `lp_quote_tech_product` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_quote_tech_version_created_from` FOREIGN KEY (`created_from_version_id`)
    REFERENCES `lp_quote_tech_data_version` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_quote_tech_version_no` CHECK (`version_no` > 0 AND `row_version` >= 0),
  CONSTRAINT `ck_quote_tech_version_status` CHECK (`version_status` IN (
    'DRAFT','SUBMITTED','RETURNED','APPROVED','VOIDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='技术产品不可变提交版本及当前草稿';

CREATE TABLE IF NOT EXISTS `lp_quote_tech_package_item` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `version_id` BIGINT NOT NULL COMMENT '技术资料版本ID',
  `line_no` INT NOT NULL COMMENT '版本内行号',
  `sort_seq` INT NOT NULL DEFAULT 0 COMMENT '展示顺序',
  `component_material_no` VARCHAR(64) NOT NULL COMMENT '包装组件料号',
  `component_name` VARCHAR(255) NOT NULL COMMENT '包装组件名称',
  `component_spec` VARCHAR(255) DEFAULT NULL COMMENT '包装组件规格',
  `quantity` DECIMAL(20,8) NOT NULL COMMENT '原始用量',
  `original_unit` VARCHAR(32) NOT NULL COMMENT '原始单位',
  `standard_quantity` DECIMAL(20,8) NOT NULL COMMENT '标准用量',
  `standard_unit` VARCHAR(32) NOT NULL COMMENT '标准单位',
  `conversion_factor` DECIMAL(20,8) NOT NULL DEFAULT 1 COMMENT '单位换算系数',
  `price_basis_type` VARCHAR(64) NOT NULL COMMENT '价格依据类型',
  `reference_unit_price` DECIMAL(20,8) DEFAULT NULL COMMENT '参考单价',
  `amount` DECIMAL(20,8) DEFAULT NULL COMMENT '包装金额',
  `source_reference_id` VARCHAR(128) DEFAULT NULL COMMENT '参照来源ID',
  `source_reference_version` VARCHAR(128) DEFAULT NULL COMMENT '参照来源版本',
  `source_snapshot_json` JSON DEFAULT NULL COMMENT '参照来源行快照',
  `remark` VARCHAR(1000) DEFAULT NULL COMMENT '备注',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_quote_tech_package_version_line` (`version_id`, `line_no`),
  KEY `idx_quote_tech_package_material` (`component_material_no`),
  CONSTRAINT `fk_quote_tech_package_version` FOREIGN KEY (`version_id`)
    REFERENCES `lp_quote_tech_data_version` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_quote_tech_package_values` CHECK (
    `line_no` > 0 AND `quantity` >= 0 AND `standard_quantity` >= 0
    AND `conversion_factor` > 0 AND (`reference_unit_price` IS NULL OR `reference_unit_price` >= 0)
    AND (`amount` IS NULL OR `amount` >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='技术资料版本包装组件明细';

CREATE TABLE IF NOT EXISTS `lp_quote_tech_aux_item` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `version_id` BIGINT NOT NULL COMMENT '技术资料版本ID',
  `line_no` INT NOT NULL COMMENT '版本内行号',
  `sort_seq` INT NOT NULL DEFAULT 0 COMMENT '展示顺序',
  `subject_code` VARCHAR(64) NOT NULL COMMENT '辅料科目编码',
  `subject_name` VARCHAR(255) DEFAULT NULL COMMENT '辅料科目名称',
  `auxiliary_name` VARCHAR(255) NOT NULL COMMENT '辅料名称',
  `pricing_method` VARCHAR(64) NOT NULL COMMENT '辅料计价方式',
  `quantity` DECIMAL(20,8) NOT NULL COMMENT '原始用量',
  `original_unit` VARCHAR(32) NOT NULL COMMENT '原始单位',
  `standard_quantity` DECIMAL(20,8) NOT NULL COMMENT '标准用量',
  `standard_unit` VARCHAR(32) NOT NULL COMMENT '标准单位',
  `conversion_factor` DECIMAL(20,8) NOT NULL DEFAULT 1 COMMENT '单位换算系数',
  `reference_unit_price` DECIMAL(20,8) NOT NULL COMMENT '参考单价',
  `amount` DECIMAL(20,8) NOT NULL COMMENT '辅料金额',
  `source_reference_id` VARCHAR(128) DEFAULT NULL COMMENT '参照来源ID',
  `source_reference_version` VARCHAR(128) DEFAULT NULL COMMENT '参照来源版本',
  `source_snapshot_json` JSON DEFAULT NULL COMMENT '参照来源行快照',
  `remark` VARCHAR(1000) DEFAULT NULL COMMENT '备注',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_quote_tech_aux_version_line` (`version_id`, `line_no`),
  KEY `idx_quote_tech_aux_subject` (`subject_code`),
  CONSTRAINT `fk_quote_tech_aux_version` FOREIGN KEY (`version_id`)
    REFERENCES `lp_quote_tech_data_version` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_quote_tech_aux_values` CHECK (
    `line_no` > 0 AND `quantity` >= 0 AND `standard_quantity` >= 0
    AND `conversion_factor` > 0 AND `reference_unit_price` >= 0 AND `amount` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='技术资料版本辅料明细';

CREATE TABLE IF NOT EXISTS `lp_quote_tech_salary_item` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `version_id` BIGINT NOT NULL COMMENT '技术资料版本ID',
  `line_no` INT NOT NULL COMMENT '版本内行号',
  `sort_seq` INT NOT NULL DEFAULT 0 COMMENT '展示顺序',
  `process_code` VARCHAR(64) NOT NULL COMMENT '工序编码',
  `process_name` VARCHAR(255) NOT NULL COMMENT '工序名称',
  `labor_type` VARCHAR(64) NOT NULL COMMENT '人工类型',
  `working_hours` DECIMAL(20,8) NOT NULL COMMENT '原始工时',
  `original_time_unit` VARCHAR(32) NOT NULL COMMENT '原始工时单位',
  `standard_hours` DECIMAL(20,8) NOT NULL COMMENT '标准小时数',
  `standard_time_unit` VARCHAR(32) NOT NULL DEFAULT 'HOUR' COMMENT '标准工时单位',
  `conversion_factor` DECIMAL(20,8) NOT NULL DEFAULT 1 COMMENT '工时换算系数',
  `hourly_rate` DECIMAL(20,8) NOT NULL COMMENT '工时单价',
  `amount` DECIMAL(20,8) NOT NULL COMMENT '工资金额',
  `source_reference_id` VARCHAR(128) DEFAULT NULL COMMENT '参照来源ID',
  `source_reference_version` VARCHAR(128) DEFAULT NULL COMMENT '参照来源版本',
  `source_snapshot_json` JSON DEFAULT NULL COMMENT '参照来源行快照',
  `remark` VARCHAR(1000) DEFAULT NULL COMMENT '备注',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_quote_tech_salary_version_line` (`version_id`, `line_no`),
  KEY `idx_quote_tech_salary_process` (`process_code`),
  CONSTRAINT `fk_quote_tech_salary_version` FOREIGN KEY (`version_id`)
    REFERENCES `lp_quote_tech_data_version` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_quote_tech_salary_values` CHECK (
    `line_no` > 0 AND `working_hours` >= 0 AND `standard_hours` >= 0
    AND `conversion_factor` > 0 AND `hourly_rate` >= 0 AND `amount` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='技术资料版本工资明细';

CREATE TABLE IF NOT EXISTS `lp_quote_tech_review_item` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `task_id` BIGINT NOT NULL COMMENT '技术资料任务ID',
  `review_round` INT NOT NULL COMMENT '审核轮次',
  `product_id` BIGINT NOT NULL COMMENT '技术产品ID',
  `submitted_version_id` BIGINT NOT NULL COMMENT '当轮提交版本ID',
  `module_type` VARCHAR(32) NOT NULL COMMENT 'PROFILE/PACKAGE/AUXILIARY/SALARY',
  `decision` VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PASSED/RETURNED',
  `decision_reason` VARCHAR(1000) DEFAULT NULL COMMENT '审核意见',
  `difference_snapshot_json` JSON DEFAULT NULL COMMENT '与上一版差异快照',
  `validation_snapshot_json` JSON DEFAULT NULL COMMENT '提交校验快照',
  `decided_by` BIGINT DEFAULT NULL COMMENT '决定人ID',
  `decided_by_name` VARCHAR(128) DEFAULT NULL COMMENT '决定人',
  `decided_at` DATETIME DEFAULT NULL COMMENT '决定时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_quote_tech_review_round_module`
    (`task_id`, `review_round`, `product_id`, `module_type`),
  KEY `idx_quote_tech_review_reviewer` (`decision`, `decided_by`, `decided_at`),
  KEY `idx_quote_tech_review_version` (`submitted_version_id`),
  CONSTRAINT `fk_quote_tech_review_task` FOREIGN KEY (`task_id`)
    REFERENCES `lp_quote_tech_task` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_quote_tech_review_product` FOREIGN KEY (`product_id`)
    REFERENCES `lp_quote_tech_product` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_quote_tech_review_version` FOREIGN KEY (`submitted_version_id`)
    REFERENCES `lp_quote_tech_data_version` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_quote_tech_review_round` CHECK (`review_round` > 0),
  CONSTRAINT `ck_quote_tech_review_module` CHECK (`module_type` IN (
    'PROFILE','PACKAGE','AUXILIARY','SALARY')),
  CONSTRAINT `ck_quote_tech_review_decision` CHECK (`decision` IN (
    'PENDING','PASSED','RETURNED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='每轮、每产品、每模块审核历史';

-- V246 结束
