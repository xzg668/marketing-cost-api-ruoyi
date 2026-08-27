-- =============================================================================
-- V239: 报价核算唯一执行链加固
--   1. 移除已下线的旧 OA/成本试算菜单与权限；
--   2. 为统一产品核算命令增加独立执行权限；
--   3. 固化业务输入修订号与结果数据质量；
--   4. 将硬编码成本系数迁入生效期规则表；
--   5. 在复用批次投影前归档每一次执行，避免重跑覆盖历史。
-- =============================================================================

SET NAMES utf8mb4;

CREATE TEMPORARY TABLE `tmp_v239_obsolete_cost_menu` (
  `menu_id` BIGINT NOT NULL PRIMARY KEY
);

INSERT IGNORE INTO `tmp_v239_obsolete_cost_menu` (`menu_id`)
SELECT `menu_id`
  FROM `sys_menu`
 WHERE `path` IN ('/ingest/oa-form', '/cost/run', '/cost/run/completed')
    OR `component` IN (
         'ingest/oa-form/index',
         'cost/run/index',
         'cost/run/completed/index',
         'pages:CostRunPage',
         'pages:CostRunResultPage',
         'pages:OaFormDetailPage'
       )
    OR `perms` LIKE 'cost:run:%';

DELETE role_menu
  FROM `sys_role_menu` role_menu
  JOIN `tmp_v239_obsolete_cost_menu` obsolete
    ON obsolete.`menu_id` = role_menu.`menu_id`;

DELETE menu
  FROM `sys_menu` menu
  JOIN `tmp_v239_obsolete_cost_menu` obsolete
    ON obsolete.`menu_id` = menu.`menu_id`;

DROP TEMPORARY TABLE `tmp_v239_obsolete_cost_menu`;

-- 菜单主键不是自增列，使用项目预留段中的固定编号，重复执行时保持幂等。
INSERT IGNORE INTO `sys_menu` (
  `menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`,
  `is_frame`, `is_cache`, `menu_type`, `visible`,
  `status`, `perms`, `icon`, `create_by`, `create_time`, `update_by`,
  `update_time`, `remark`, `business_unit_type`
)
SELECT
  40520, '执行报价核算', quote_menu.`menu_id`, 10, '', '',
  1, 0, 'F', '0',
  '0', 'ingest:quote:cost-run:execute', '#', 'admin', NOW(), '',
  NOW(), '发起整单或单品统一核算命令', NULL
FROM `sys_menu` quote_menu
WHERE quote_menu.`perms` = 'ingest:quote:list'
ORDER BY quote_menu.`menu_id`
LIMIT 1;

-- 保持升级前的可用角色范围：原来能访问报价列表的角色获得执行权限。
INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT quote_role.`role_id`, 40520
  FROM `sys_role_menu` quote_role
 WHERE quote_role.`menu_id` = (
   SELECT quote_menu.`menu_id`
     FROM `sys_menu` quote_menu
    WHERE quote_menu.`perms` = 'ingest:quote:list'
    ORDER BY quote_menu.`menu_id`
    LIMIT 1
 );

ALTER TABLE `lp_quote_cost_run_version`
  ADD COLUMN `source_revision` CHAR(64) NULL COMMENT '核算采用的上游业务输入修订号' AFTER `input_fingerprint`,
  ADD COLUMN `data_quality_status` VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN' COMMENT '结果质量：COMPLETE/WARNING/UNKNOWN' AFTER `algorithm_version`,
  ADD COLUMN `data_quality_warning_count` INT NOT NULL DEFAULT 0 COMMENT '结果质量警告数' AFTER `data_quality_status`,
  ADD COLUMN `data_quality_summary` VARCHAR(1000) NULL COMMENT '结果质量摘要' AFTER `data_quality_warning_count`,
  ADD KEY `idx_quote_cost_source_revision` (`source_revision`),
  ADD KEY `idx_quote_cost_quality` (`data_quality_status`);

ALTER TABLE `lp_quote_costing_workspace`
  ADD COLUMN `source_revision` CHAR(64) NULL COMMENT '最近检查到的上游业务输入修订号' AFTER `input_fingerprint`,
  ADD COLUMN `last_success_source_revision` CHAR(64) NULL COMMENT '当前成功成本采用的上游业务输入修订号' AFTER `last_success_input_fingerprint`,
  ADD COLUMN `data_quality_status` VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN' COMMENT '当前成功结果质量' AFTER `carried_forward_price_count`,
  ADD COLUMN `data_quality_warning_count` INT NOT NULL DEFAULT 0 COMMENT '当前成功结果质量警告数' AFTER `data_quality_status`,
  ADD COLUMN `data_quality_summary` VARCHAR(1000) NULL COMMENT '当前成功结果质量摘要' AFTER `data_quality_warning_count`;

ALTER TABLE `lp_cost_run_batch`
  ADD COLUMN `source_revision` CHAR(64) NULL COMMENT '本次报价批次提交时的上游业务输入修订号' AFTER `business_unit_type`,
  DROP INDEX `idx_cost_run_batch_prerequisite`,
  DROP COLUMN `prerequisite_status`;

ALTER TABLE `lp_cost_run_task`
  ADD COLUMN `source_revision` CHAR(64) NULL COMMENT '本次产品任务提交时的上游业务输入修订号' AFTER `pricing_month`,
  ADD KEY `idx_cost_run_task_source_revision` (`source_revision`);

CREATE TABLE IF NOT EXISTS `lp_cost_business_rule` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `rule_code` VARCHAR(64) NOT NULL COMMENT '稳定规则编码',
  `business_unit_type` VARCHAR(32) NOT NULL DEFAULT '*' COMMENT '业务单元，* 表示全局',
  `effective_from` CHAR(7) NOT NULL COMMENT '生效月份 YYYY-MM',
  `effective_to` CHAR(7) NULL COMMENT '失效月份 YYYY-MM（含）',
  `decimal_value` DECIMAL(20,8) NULL COMMENT '数值型规则值',
  `text_value` VARCHAR(255) NULL COMMENT '文本型规则值',
  `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
  `description` VARCHAR(500) NULL COMMENT '规则业务说明',
  `created_by` VARCHAR(64) NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_by` VARCHAR(64) NULL,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cost_rule_period` (`rule_code`, `business_unit_type`, `effective_from`),
  KEY `idx_cost_rule_lookup` (`rule_code`, `business_unit_type`, `enabled`, `effective_from`, `effective_to`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='版本化成本业务规则';

INSERT IGNORE INTO `lp_cost_business_rule` (
  `rule_code`, `business_unit_type`, `effective_from`, `decimal_value`,
  `description`, `created_by`, `updated_by`
) VALUES
  ('CMS_AUX_UPLIFT_RATE', '*', '2000-01', 1.05000000,
   'CMS 辅料成本上浮系数；替代原代码常量 1.05', 'migration-v239', 'migration-v239'),
  ('PACKAGE_COMPONENT_COEFFICIENT', '*', '2000-01', 1.05000000,
   '包装组件成本系数；替代原代码常量 1.05', 'migration-v239', 'migration-v239');

CREATE TABLE IF NOT EXISTS `lp_cost_run_execution_history` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `batch_id` BIGINT NOT NULL,
  `batch_no` VARCHAR(64) NOT NULL,
  `execution_no` INT NOT NULL,
  `scene` VARCHAR(32) NOT NULL,
  `source_no` VARCHAR(64) NOT NULL,
  `pricing_month` VARCHAR(7) NULL,
  `business_unit_type` VARCHAR(32) NULL,
  `source_revision` CHAR(64) NULL,
  `status` VARCHAR(32) NOT NULL,
  `total_count` INT NOT NULL DEFAULT 0,
  `success_count` INT NOT NULL DEFAULT 0,
  `failed_count` INT NOT NULL DEFAULT 0,
  `skipped_count` INT NOT NULL DEFAULT 0,
  `progress` INT NOT NULL DEFAULT 0,
  `request_snapshot_json` JSON NULL,
  `result_summary_json` JSON NULL,
  `error_message` VARCHAR(1000) NULL,
  `error_stack` TEXT NULL,
  `created_by` VARCHAR(64) NULL,
  `created_name` VARCHAR(128) NULL,
  `started_at` DATETIME NULL,
  `finished_at` DATETIME NULL,
  `original_created_at` DATETIME NOT NULL,
  `original_updated_at` DATETIME NOT NULL,
  `archived_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cost_execution_history` (`batch_no`, `execution_no`),
  KEY `idx_cost_execution_source` (`scene`, `source_no`, `pricing_month`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='核算批次不可变执行历史';

CREATE TABLE IF NOT EXISTS `lp_cost_run_task_history` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `task_id` BIGINT NOT NULL,
  `batch_no` VARCHAR(64) NOT NULL,
  `execution_no` INT NOT NULL,
  `cost_run_version_id` BIGINT NULL,
  `cost_run_no` VARCHAR(64) NULL,
  `scene` VARCHAR(32) NOT NULL,
  `source_no` VARCHAR(64) NOT NULL,
  `calc_object_key` VARCHAR(128) NOT NULL,
  `oa_no` VARCHAR(64) NOT NULL,
  `oa_form_item_id` BIGINT NULL,
  `product_code` VARCHAR(64) NOT NULL,
  `business_unit_type` VARCHAR(32) NULL,
  `pricing_month` VARCHAR(7) NULL,
  `source_revision` CHAR(64) NULL,
  `status` VARCHAR(32) NOT NULL,
  `progress` INT NOT NULL DEFAULT 0,
  `retry_count` INT NOT NULL DEFAULT 0,
  `request_snapshot_json` JSON NULL,
  `result_summary_json` JSON NULL,
  `error_message` VARCHAR(1000) NULL,
  `error_stack` TEXT NULL,
  `started_at` DATETIME NULL,
  `finished_at` DATETIME NULL,
  `original_created_at` DATETIME NOT NULL,
  `original_updated_at` DATETIME NOT NULL,
  `archived_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cost_task_history` (`task_id`, `execution_no`),
  KEY `idx_cost_task_history_batch` (`batch_no`, `execution_no`, `status`),
  KEY `idx_cost_task_history_quote` (`oa_no`, `oa_form_item_id`, `pricing_month`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='核算任务不可变执行历史';
