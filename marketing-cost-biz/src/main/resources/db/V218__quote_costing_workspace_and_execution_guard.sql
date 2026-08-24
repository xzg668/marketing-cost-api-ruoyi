-- =============================================================================
-- V218: 报价核算当前工作区与批次执行隔离
--
-- 目标：
--   1. 每个 OA 产品行 + 核算月份只保留一条当前工作区主记录。
--   2. 批次/任务增加执行轮次，旧轮次任务不能被新轮次领取或计入进度。
--   3. 批次前置状态为后续“整张 OA 主档只同步一次”提供并发控制点。
--
-- 本迁移只新增表/列/索引，不覆盖历史成功成本、最终价格或产品确认指针。
-- =============================================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `lp_quote_costing_workspace` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `oa_no` VARCHAR(64) NOT NULL COMMENT 'OA单号',
  `oa_form_item_id` BIGINT NOT NULL COMMENT 'OA产品行ID',
  `product_code` VARCHAR(64) NOT NULL COMMENT '产品料号',
  `period_month` CHAR(7) NOT NULL COMMENT '核算月份YYYY-MM',
  `business_unit_type` VARCHAR(32) NOT NULL COMMENT '业务单元',
  `workspace_status` VARCHAR(32) NOT NULL DEFAULT 'NOT_STARTED' COMMENT '当前核算状态',
  `current_step` VARCHAR(32) NOT NULL DEFAULT 'PRODUCT_DETAIL' COMMENT '当前业务步骤',
  `input_fingerprint` CHAR(64) DEFAULT NULL COMMENT '当前完整输入指纹',
  `last_success_input_fingerprint` CHAR(64) DEFAULT NULL COMMENT '最近成功输入指纹',
  `bom_source_fingerprint` CHAR(64) DEFAULT NULL COMMENT 'BOM来源指纹',
  `bom_rule_fingerprint` CHAR(64) DEFAULT NULL COMMENT 'BOM规则指纹',
  `current_bom_build_batch_id` VARCHAR(64) DEFAULT NULL COMMENT '当前BOM构建批次',
  `current_prepare_no` VARCHAR(64) DEFAULT NULL COMMENT '当前最终价格批次',
  `current_cost_version_id` BIGINT DEFAULT NULL COMMENT '当前成功成本版本',
  `gap_count` INT NOT NULL DEFAULT 0 COMMENT '当前阻断缺口数',
  `carried_forward_price_count` INT NOT NULL DEFAULT 0 COMMENT '沿用历史价数量',
  `stale_reason_code` VARCHAR(64) DEFAULT NULL COMMENT '待重算原因编码',
  `last_task_id` BIGINT DEFAULT NULL COMMENT '最近产品任务ID',
  `lock_version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `last_checked_at` DATETIME DEFAULT NULL COMMENT '最近完成检查时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_quote_costing_workspace_item_month` (`oa_form_item_id`, `period_month`),
  KEY `idx_quote_costing_workspace_oa` (`oa_no`, `period_month`),
  KEY `idx_quote_costing_workspace_status` (`workspace_status`, `period_month`),
  KEY `idx_quote_costing_workspace_task` (`last_task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='报价产品每月唯一当前核算工作区';

DROP PROCEDURE IF EXISTS v218_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v218_add_index_if_not_exists;

DELIMITER $$

CREATE PROCEDURE v218_add_column_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_column_name VARCHAR(64),
  IN p_column_def TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.TABLES
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table_name
  ) AND NOT EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table_name
       AND COLUMN_NAME = p_column_name
  ) THEN
    SET @sql = CONCAT('ALTER TABLE `', p_table_name, '` ADD COLUMN ', p_column_def);
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

CREATE PROCEDURE v218_add_index_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_index_name VARCHAR(64),
  IN p_index_def TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.TABLES
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table_name
  ) AND NOT EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table_name
       AND INDEX_NAME = p_index_name
  ) THEN
    SET @sql = CONCAT('ALTER TABLE `', p_table_name, '` ADD ', p_index_def);
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

DELIMITER ;

CALL v218_add_column_if_not_exists(
  'lp_cost_run_batch', 'execution_no',
  '`execution_no` INT NOT NULL DEFAULT 1 COMMENT ''当前执行轮次'' AFTER `business_unit_type`'
);
CALL v218_add_column_if_not_exists(
  'lp_cost_run_batch', 'prerequisite_status',
  '`prerequisite_status` VARCHAR(32) NOT NULL DEFAULT ''NOT_REQUIRED'' COMMENT ''前置状态：NOT_REQUIRED/PENDING/RUNNING/SUCCESS/FAILED'' AFTER `execution_no`'
);
CALL v218_add_column_if_not_exists(
  'lp_cost_run_batch', 'control_version',
  '`control_version` INT NOT NULL DEFAULT 0 COMMENT ''批次前置状态乐观锁版本'' AFTER `prerequisite_status`'
);
CALL v218_add_column_if_not_exists(
  'lp_cost_run_task', 'execution_no',
  '`execution_no` INT NOT NULL DEFAULT 1 COMMENT ''所属批次执行轮次'' AFTER `batch_no`'
);

UPDATE lp_cost_run_batch
   SET prerequisite_status = 'SUCCESS'
 WHERE scene = 'QUOTE'
   AND prerequisite_status = 'NOT_REQUIRED';

CALL v218_add_index_if_not_exists(
  'lp_cost_run_batch', 'idx_cost_run_batch_prerequisite',
  'KEY `idx_cost_run_batch_prerequisite` (`scene`, `prerequisite_status`, `status`)'
);
CALL v218_add_index_if_not_exists(
  'lp_cost_run_task', 'idx_cost_run_task_batch_execution',
  'KEY `idx_cost_run_task_batch_execution` (`batch_no`, `execution_no`, `status`)'
);

DROP PROCEDURE IF EXISTS v218_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v218_add_index_if_not_exists;
