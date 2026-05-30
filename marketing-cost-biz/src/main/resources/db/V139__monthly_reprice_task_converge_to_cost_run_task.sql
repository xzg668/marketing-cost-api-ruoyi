-- =============================================================================
-- V139: 月度调价任务表收敛到通用成本核算任务表
-- -----------------------------------------------------------------------------
-- 目标：
--   1. 将历史 lp_monthly_reprice_task 数据迁移到 lp_cost_run_batch / lp_cost_run_task。
--   2. 删除旧月度调价任务表，月度调价和普通报价统一由 lp_cost_run_task.scene 区分。
-- =============================================================================

SET NAMES utf8mb4;

INSERT IGNORE INTO lp_cost_run_batch (
  batch_no,
  scene,
  source_no,
  pricing_month,
  price_as_of_time,
  business_unit_type,
  status,
  total_count,
  success_count,
  failed_count,
  skipped_count,
  progress,
  request_snapshot_json,
  created_by,
  created_name,
  started_at,
  finished_at,
  created_at,
  updated_at
)
SELECT
  CONCAT('CRM-MR-', b.id) AS batch_no,
  'MONTHLY_REPRICE' AS scene,
  b.reprice_no AS source_no,
  b.pricing_month,
  b.price_as_of_time,
  b.business_unit_type,
  CASE
    WHEN b.status = 'WAIT_CONFIRM' THEN 'SUCCESS'
    WHEN b.status = 'CONFIRMED' THEN 'SUCCESS'
    WHEN b.status = 'CANCELLED' THEN 'CANCELED'
    ELSE b.status
  END AS status,
  b.total_count,
  b.success_count,
  b.failed_count,
  b.skipped_count,
  CASE
    WHEN b.total_count <= 0 THEN 0
    WHEN b.success_count + b.failed_count + b.skipped_count >= b.total_count THEN 100
    ELSE FLOOR((b.success_count + b.failed_count + b.skipped_count) * 100 / b.total_count)
  END AS progress,
  JSON_OBJECT(
    'scene', 'MONTHLY_REPRICE',
    'repriceNo', b.reprice_no,
    'migratedFrom', 'lp_monthly_reprice_task'
  ) AS request_snapshot_json,
  b.created_by,
  b.created_name,
  b.started_at,
  b.finished_at,
  b.created_at,
  b.updated_at
FROM lp_monthly_reprice_batch b
WHERE EXISTS (
  SELECT 1
    FROM lp_monthly_reprice_task t
   WHERE t.reprice_no = b.reprice_no
)
  AND NOT EXISTS (
    SELECT 1
      FROM lp_cost_run_batch cb
     WHERE cb.scene = 'MONTHLY_REPRICE'
       AND cb.source_no = b.reprice_no
       AND cb.pricing_month = b.pricing_month
       AND cb.business_unit_type = b.business_unit_type
  );

INSERT IGNORE INTO lp_cost_run_task (
  batch_no,
  scene,
  source_no,
  calc_object_key,
  oa_no,
  oa_form_item_id,
  product_code,
  package_method,
  customer_name,
  business_unit_type,
  pricing_month,
  price_as_of_time,
  adjust_batch_id,
  bom_source_policy,
  status,
  progress,
  worker_id,
  locked_at,
  lock_expire_time,
  retry_count,
  max_retry_count,
  request_snapshot_json,
  error_message,
  started_at,
  finished_at,
  created_at,
  updated_at
)
SELECT
  cb.batch_no,
  'MONTHLY_REPRICE' AS scene,
  t.reprice_no AS source_no,
  t.calc_object_key,
  t.oa_no,
  t.oa_form_item_id,
  t.product_code,
  t.package_method,
  t.customer_name,
  t.business_unit_type,
  t.pricing_month,
  b.price_as_of_time,
  b.adjust_batch_id,
  b.bom_source_policy,
  CASE
    WHEN t.status = 'CANCELLED' THEN 'CANCELED'
    ELSE t.status
  END AS status,
  CASE
    WHEN t.status = 'SUCCESS' THEN 100
    WHEN t.status = 'FAILED' THEN 100
    WHEN t.status = 'CANCELLED' THEN 100
    WHEN t.status = 'RUNNING' THEN 1
    ELSE 0
  END AS progress,
  t.worker_id,
  t.locked_at,
  t.lock_expire_time,
  t.retry_count,
  3 AS max_retry_count,
  JSON_OBJECT(
    'repriceNo', t.reprice_no,
    'sourceOaCalcStatus', t.source_oa_calc_status,
    'migratedFrom', 'lp_monthly_reprice_task'
  ) AS request_snapshot_json,
  t.last_error_message,
  t.started_at,
  t.finished_at,
  t.created_at,
  t.updated_at
FROM lp_monthly_reprice_task t
JOIN lp_monthly_reprice_batch b
  ON b.reprice_no = t.reprice_no
JOIN lp_cost_run_batch cb
  ON cb.scene = 'MONTHLY_REPRICE'
 AND cb.source_no = b.reprice_no
 AND cb.pricing_month = b.pricing_month
 AND cb.business_unit_type = b.business_unit_type;

DROP TABLE IF EXISTS lp_monthly_reprice_task;
