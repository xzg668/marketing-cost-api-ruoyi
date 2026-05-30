-- =============================================================================
-- V137: 通用成本核算任务队列表
-- -----------------------------------------------------------------------------
-- 目标：
--   1. 建立普通报价 QUOTE 与月度调价 MONTHLY_REPRICE 共用的 DB 任务队列。
--   2. 任务账本独立于普通报价和月度调价结果表，不改变现有结果表结构和数据。
--   3. 为后续 CostRunTaskSubmissionService / CostRunTaskClaimService 提供幂等提交、
--      原子抢占、失败重试、进度汇总所需字段与索引。
-- =============================================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS lp_cost_run_batch (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  batch_no VARCHAR(64) NOT NULL COMMENT '通用核算批次号',
  scene VARCHAR(32) NOT NULL COMMENT '核算场景：QUOTE/MONTHLY_REPRICE',
  source_no VARCHAR(64) NOT NULL COMMENT '来源单号：普通报价为oa_no，月度调价为reprice_no',
  pricing_month VARCHAR(7) DEFAULT NULL COMMENT '计价月份 YYYY-MM',
  price_as_of_time DATETIME DEFAULT NULL COMMENT '价格版本时点',
  business_unit_type VARCHAR(32) DEFAULT NULL COMMENT '业务单元',
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '批次状态：PENDING/RUNNING/SUCCESS/PARTIAL_FAILED/FAILED/CANCELED',
  total_count INT NOT NULL DEFAULT 0 COMMENT '任务总数',
  success_count INT NOT NULL DEFAULT 0 COMMENT '成功数',
  failed_count INT NOT NULL DEFAULT 0 COMMENT '失败数',
  skipped_count INT NOT NULL DEFAULT 0 COMMENT '跳过数',
  progress INT NOT NULL DEFAULT 0 COMMENT '总进度 0-100',
  request_snapshot_json JSON DEFAULT NULL COMMENT '发起时关键参数快照',
  result_summary_json JSON DEFAULT NULL COMMENT '结果摘要',
  error_message VARCHAR(1000) DEFAULT NULL COMMENT '批次错误摘要',
  error_stack TEXT DEFAULT NULL COMMENT '批次错误堆栈摘要',
  created_by VARCHAR(64) DEFAULT NULL COMMENT '发起人ID',
  created_name VARCHAR(128) DEFAULT NULL COMMENT '发起人姓名',
  started_at DATETIME DEFAULT NULL COMMENT '开始时间',
  finished_at DATETIME DEFAULT NULL COMMENT '结束时间',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_cost_run_batch_no (batch_no),
  UNIQUE KEY uk_cost_run_batch_source (scene, source_no, pricing_month, business_unit_type),
  KEY idx_cost_run_batch_scene_source (scene, source_no),
  KEY idx_cost_run_batch_status_scene (status, scene),
  KEY idx_cost_run_batch_bu_status (business_unit_type, status),
  KEY idx_cost_run_batch_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='通用成本核算批次表';

CREATE TABLE IF NOT EXISTS lp_cost_run_task (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  batch_no VARCHAR(64) NOT NULL COMMENT '通用核算批次号',
  scene VARCHAR(32) NOT NULL COMMENT '核算场景：QUOTE/MONTHLY_REPRICE',
  source_no VARCHAR(64) NOT NULL COMMENT '来源单号：普通报价为oa_no，月度调价为reprice_no',
  calc_object_key VARCHAR(128) NOT NULL COMMENT '核算对象唯一键',
  oa_no VARCHAR(64) NOT NULL COMMENT 'OA单号',
  oa_form_item_id BIGINT DEFAULT NULL COMMENT 'OA明细行ID oa_form_item.id',
  product_code VARCHAR(64) NOT NULL COMMENT '产品料号',
  package_method VARCHAR(128) DEFAULT NULL COMMENT '包装方式',
  customer_name VARCHAR(255) DEFAULT NULL COMMENT '客户名称',
  business_unit_type VARCHAR(32) DEFAULT NULL COMMENT '业务单元',
  pricing_month VARCHAR(7) DEFAULT NULL COMMENT '计价月份 YYYY-MM',
  price_as_of_time DATETIME DEFAULT NULL COMMENT '价格版本时点',
  adjust_batch_id BIGINT DEFAULT NULL COMMENT '月度调价影响因素批次ID lp_factor_adjust_batch.id',
  bom_source_policy VARCHAR(32) DEFAULT NULL COMMENT 'BOM来源策略',
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '任务状态：PENDING/RUNNING/SUCCESS/FAILED/RETRYABLE/CANCELED',
  progress INT NOT NULL DEFAULT 0 COMMENT '单任务进度 0-100',
  worker_id VARCHAR(128) DEFAULT NULL COMMENT '当前持有Worker',
  locked_at DATETIME DEFAULT NULL COMMENT '锁定时间',
  lock_expire_time DATETIME DEFAULT NULL COMMENT '锁过期时间',
  retry_count INT NOT NULL DEFAULT 0 COMMENT '已重试次数',
  max_retry_count INT NOT NULL DEFAULT 3 COMMENT '最大重试次数',
  request_snapshot_json JSON DEFAULT NULL COMMENT '任务请求快照',
  result_summary_json JSON DEFAULT NULL COMMENT '结果摘要',
  error_message VARCHAR(1000) DEFAULT NULL COMMENT '错误摘要',
  error_stack TEXT DEFAULT NULL COMMENT '错误堆栈摘要',
  started_at DATETIME DEFAULT NULL COMMENT '任务开始时间',
  finished_at DATETIME DEFAULT NULL COMMENT '任务结束时间',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_cost_run_task_batch_object (batch_no, calc_object_key),
  KEY idx_cost_run_task_claim (status, lock_expire_time, id),
  KEY idx_cost_run_task_scene_claim (scene, status, lock_expire_time, id),
  KEY idx_cost_run_task_scene_source (scene, source_no),
  KEY idx_cost_run_task_worker (worker_id, status),
  KEY idx_cost_run_task_batch_status (batch_no, status),
  KEY idx_cost_run_task_oa (oa_no),
  KEY idx_cost_run_task_product (product_code),
  KEY idx_cost_run_task_adjust_batch (adjust_batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='通用成本核算任务表';
