-- =============================================================================
-- 月度调价压测数据生成脚本
-- -----------------------------------------------------------------------------
-- 使用方式：
--   1. 只在测试库执行，禁止在生产库执行。
--   2. 可在执行前覆盖变量，例如：
--      SET @perf_reprice_no = 'PERF-202605-100K';
--      SET @perf_object_count = 100000;
--      SET @perf_bom_lines = 30;
--      SET @perf_seed_results = 1;
--      SOURCE marketing-cost-api/scripts/monthly_reprice_perf_seed.sql;
--
-- 说明：
--   - 脚本只清理同一个 PERF reprice_no 的测试数据，不触碰日常 OA 结果表。
--   - @perf_seed_results = 1 用于查询/分页/明细下钻压测。
--   - @perf_seed_results = 0 会只生成 PENDING 任务，用于 Worker 抢任务/恢复压测。
-- =============================================================================

SET NAMES utf8mb4;
SET SESSION cte_max_recursion_depth = 1000000;

SET @perf_reprice_no = COALESCE(NULLIF(@perf_reprice_no, ''), CONCAT('PERF-', DATE_FORMAT(NOW(), '%Y%m%d%H%i%s')));
SET @perf_pricing_month = COALESCE(NULLIF(@perf_pricing_month, ''), DATE_FORMAT(CURRENT_DATE, '%Y-%m'));
SET @perf_business_unit_type = COALESCE(NULLIF(@perf_business_unit_type, ''), 'PERF_BU');
SET @perf_adjust_batch_id = COALESCE(@perf_adjust_batch_id, 1);
SET @perf_object_count = COALESCE(@perf_object_count, 10000);
SET @perf_bom_lines = COALESCE(@perf_bom_lines, 30);
SET @perf_seed_results = COALESCE(@perf_seed_results, 1);
SET @perf_cost_batch_no = CONCAT('CRM-PERF-', LEFT(MD5(@perf_reprice_no), 16));

DELETE FROM lp_monthly_reprice_cost_item WHERE reprice_no = @perf_reprice_no;
DELETE FROM lp_monthly_reprice_part_item WHERE reprice_no = @perf_reprice_no;
DELETE FROM lp_monthly_reprice_result WHERE reprice_no = @perf_reprice_no;
DELETE FROM lp_monthly_reprice_audit_log WHERE reprice_no = @perf_reprice_no;
DELETE FROM lp_cost_run_task WHERE scene = 'MONTHLY_REPRICE' AND source_no = @perf_reprice_no;
DELETE FROM lp_cost_run_batch WHERE scene = 'MONTHLY_REPRICE' AND source_no = @perf_reprice_no;
DELETE FROM lp_monthly_reprice_batch WHERE reprice_no = @perf_reprice_no;

INSERT INTO lp_monthly_reprice_batch (
  reprice_no, pricing_month, business_unit_type, adjust_batch_id, execution_backend,
  status, total_count, success_count, failed_count, skipped_count, cost_engine_version,
  price_version, rule_version, created_by, created_name, started_at, finished_at, remark
) VALUES (
  @perf_reprice_no, @perf_pricing_month, @perf_business_unit_type, @perf_adjust_batch_id, 'LOCAL_WORKER',
  IF(@perf_seed_results = 1, 'WAIT_CONFIRM', 'RUNNING'),
  @perf_object_count,
  IF(@perf_seed_results = 1, @perf_object_count - FLOOR(@perf_object_count / 997), 0),
  IF(@perf_seed_results = 1, FLOOR(@perf_object_count / 997), 0),
  0, 'PERF_SYNTHETIC', @perf_pricing_month, 'PERF_RULE',
  'perf', '性能压测', NOW(), IF(@perf_seed_results = 1, NOW(), NULL),
  CONCAT('synthetic objects=', @perf_object_count, ', bomLines=', @perf_bom_lines)
);

INSERT INTO lp_cost_run_batch (
  batch_no, scene, source_no, pricing_month, price_as_of_time, business_unit_type,
  status, total_count, success_count, failed_count, skipped_count, progress,
  request_snapshot_json, created_by, created_name, started_at, finished_at
) VALUES (
  @perf_cost_batch_no, 'MONTHLY_REPRICE', @perf_reprice_no, @perf_pricing_month, NOW(),
  @perf_business_unit_type,
  IF(@perf_seed_results = 1, IF(FLOOR(@perf_object_count / 997) > 0, 'PARTIAL_FAILED', 'SUCCESS'), 'PENDING'),
  @perf_object_count,
  IF(@perf_seed_results = 1, @perf_object_count - FLOOR(@perf_object_count / 997), 0),
  IF(@perf_seed_results = 1, FLOOR(@perf_object_count / 997), 0),
  0,
  IF(@perf_seed_results = 1, 100, 0),
  JSON_OBJECT('scene', 'MONTHLY_REPRICE', 'repriceNo', @perf_reprice_no, 'seed', 'PERF'),
  'perf', '性能压测', NOW(), IF(@perf_seed_results = 1, NOW(), NULL)
);

INSERT INTO lp_cost_run_task (
  batch_no, scene, source_no, calc_object_key, oa_no, oa_form_item_id, product_code,
  package_method, customer_name, business_unit_type, pricing_month, price_as_of_time,
  adjust_batch_id, bom_source_policy, status, progress, retry_count, max_retry_count,
  request_snapshot_json, error_message
)
WITH RECURSIVE seq(n) AS (
  SELECT 1
  UNION ALL
  SELECT n + 1 FROM seq WHERE n < @perf_object_count
)
SELECT
  @perf_cost_batch_no,
  'MONTHLY_REPRICE',
  @perf_reprice_no,
  CONCAT('PERF|', LPAD(n, 8, '0'), '|', CASE n % 3 WHEN 0 THEN 'CARTON' WHEN 1 THEN 'WOOD' ELSE 'PALLET' END),
  CONCAT('PERF-OA-', LPAD(((n - 1) DIV 500) + 1, 6, '0')),
  n,
  CONCAT('PERF-P', LPAD((n % 3000) + 1, 6, '0')),
  CASE n % 3 WHEN 0 THEN '纸箱' WHEN 1 THEN '木箱' ELSE '托盘' END,
  CONCAT('压测客户-', LPAD((n % 200) + 1, 3, '0')),
  @perf_business_unit_type,
  @perf_pricing_month,
  NOW(),
  @perf_adjust_batch_id,
  'HISTORICAL_OA_BOM',
  IF(@perf_seed_results = 1, IF(n % 997 = 0, 'FAILED', 'SUCCESS'), 'PENDING'),
  IF(@perf_seed_results = 1, 100, 0),
  0,
  3,
  JSON_OBJECT('repriceNo', @perf_reprice_no, 'sourceOaCalcStatus', '已核算', 'syntheticNo', n),
  IF(@perf_seed_results = 1 AND n % 997 = 0, '缺失影响因素价格/公式异常/物料缺失混合样例', NULL)
FROM seq;

INSERT INTO lp_monthly_reprice_result (
  reprice_no, pricing_month, business_unit_type, oa_no, oa_form_item_id, product_code,
  package_method, customer_name, calc_object_key, total_cost, material_cost, labor_cost,
  auxiliary_cost, manufacturing_cost, management_cost, sales_cost, finance_cost,
  cost_engine_version, price_version, rule_version, calc_status, calc_message
)
WITH RECURSIVE seq(n) AS (
  SELECT 1
  UNION ALL
  SELECT n + 1 FROM seq WHERE n < @perf_object_count
)
SELECT
  @perf_reprice_no,
  @perf_pricing_month,
  @perf_business_unit_type,
  CONCAT('PERF-OA-', LPAD(((n - 1) DIV 500) + 1, 6, '0')),
  n,
  CONCAT('PERF-P', LPAD((n % 3000) + 1, 6, '0')),
  CASE n % 3 WHEN 0 THEN '纸箱' WHEN 1 THEN '木箱' ELSE '托盘' END,
  CONCAT('压测客户-', LPAD((n % 200) + 1, 3, '0')),
  CONCAT('PERF|', LPAD(n, 8, '0'), '|', CASE n % 3 WHEN 0 THEN 'CARTON' WHEN 1 THEN 'WOOD' ELSE 'PALLET' END),
  100 + (n % 1000), 70 + (n % 500), 8 + (n % 20), 3 + (n % 10),
  5 + (n % 20), 4 + (n % 10), 2 + (n % 8), 1 + (n % 6),
  'PERF_SYNTHETIC', @perf_pricing_month, 'PERF_RULE',
  IF(n % 997 = 0, 'FAILED', 'SUCCESS'),
  IF(n % 997 = 0, '缺失影响因素价格/公式异常/物料缺失混合样例', 'OK')
FROM seq
WHERE @perf_seed_results = 1;

INSERT INTO lp_monthly_reprice_part_item (
  reprice_no, pricing_month, business_unit_type, oa_no, calc_object_key, product_code,
  package_method, customer_name, line_no, part_code, part_name, quantity, unit_price,
  amount, price_source, calc_status, calc_message
)
WITH RECURSIVE obj(n) AS (
  SELECT 1
  UNION ALL
  SELECT n + 1 FROM obj WHERE n < @perf_object_count
),
line_seq(line_no) AS (
  SELECT 1
  UNION ALL
  SELECT line_no + 1 FROM line_seq WHERE line_no < @perf_bom_lines
)
SELECT
  @perf_reprice_no,
  @perf_pricing_month,
  @perf_business_unit_type,
  CONCAT('PERF-OA-', LPAD(((n - 1) DIV 500) + 1, 6, '0')),
  CONCAT('PERF|', LPAD(n, 8, '0'), '|', CASE n % 3 WHEN 0 THEN 'CARTON' WHEN 1 THEN 'WOOD' ELSE 'PALLET' END),
  CONCAT('PERF-P', LPAD((n % 3000) + 1, 6, '0')),
  CASE n % 3 WHEN 0 THEN '纸箱' WHEN 1 THEN '木箱' ELSE '托盘' END,
  CONCAT('压测客户-', LPAD((n % 200) + 1, 3, '0')),
  line_no,
  CONCAT('PERF-M', LPAD((line_no % 10000) + 1, 6, '0')),
  CONCAT('压测物料-', line_no),
  1 + (line_no % 7),
  2 + (line_no % 17),
  (1 + (line_no % 7)) * (2 + (line_no % 17)),
  IF(line_no % 19 = 0, '缺失影响因素价格', '月度联动价'),
  IF(n % 997 = 0 OR line_no % 997 = 0, 'FAILED', 'SUCCESS'),
  IF(n % 997 = 0 OR line_no % 997 = 0, '缺失影响因素价格/公式异常/物料缺失混合样例', 'OK')
FROM obj
JOIN line_seq
WHERE @perf_seed_results = 1;

INSERT INTO lp_monthly_reprice_cost_item (
  reprice_no, pricing_month, business_unit_type, oa_no, calc_object_key, product_code,
  package_method, customer_name, line_no, cost_item_code, cost_item_name, base_amount,
  rate, amount, calc_formula, calc_status, calc_message
)
WITH RECURSIVE seq(n) AS (
  SELECT 1
  UNION ALL
  SELECT n + 1 FROM seq WHERE n < @perf_object_count
),
cost_codes AS (
  SELECT 1 AS line_no, 'TOTAL' AS code, '成本合计' AS name
  UNION ALL SELECT 2, 'MATERIAL', '材料费'
  UNION ALL SELECT 3, 'DIRECT_LABOR', '直接人工'
  UNION ALL SELECT 4, 'MANUFACTURE_COST', '制造费用'
  UNION ALL SELECT 5, 'MGMT_EXP', '管理费用'
  UNION ALL SELECT 6, 'SALES_EXP', '销售费用'
  UNION ALL SELECT 7, 'FIN_EXP', '财务费用'
  UNION ALL SELECT 8, 'AUX_001', '辅料费'
)
SELECT
  @perf_reprice_no,
  @perf_pricing_month,
  @perf_business_unit_type,
  CONCAT('PERF-OA-', LPAD(((n - 1) DIV 500) + 1, 6, '0')),
  CONCAT('PERF|', LPAD(n, 8, '0'), '|', CASE n % 3 WHEN 0 THEN 'CARTON' WHEN 1 THEN 'WOOD' ELSE 'PALLET' END),
  CONCAT('PERF-P', LPAD((n % 3000) + 1, 6, '0')),
  CASE n % 3 WHEN 0 THEN '纸箱' WHEN 1 THEN '木箱' ELSE '托盘' END,
  CONCAT('压测客户-', LPAD((n % 200) + 1, 3, '0')),
  line_no, code, name, 100 + (n % 100), 0.01000000 * line_no,
  CASE code WHEN 'TOTAL' THEN 100 + (n % 1000) ELSE line_no * 3 + (n % 20) END,
  CONCAT('PERF_FORMULA_', code),
  IF(n % 997 = 0, 'FAILED', 'SUCCESS'),
  IF(n % 997 = 0, '公式异常样例', 'OK')
FROM seq
JOIN cost_codes
WHERE @perf_seed_results = 1;

INSERT INTO lp_monthly_reprice_audit_log (
  reprice_no, pricing_month, business_unit_type, operation_type, operation_name,
  operator_id, operator_name, target_type, target_key, after_json, change_summary
) VALUES (
  @perf_reprice_no, @perf_pricing_month, @perf_business_unit_type,
  'PERF_SEED', '生成月度调价压测数据', 'perf', '性能压测',
  'BATCH', @perf_reprice_no,
  JSON_OBJECT('objectCount', @perf_object_count, 'bomLines', @perf_bom_lines, 'seedResults', @perf_seed_results),
  CONCAT('生成压测对象 ', @perf_object_count, ' 个，BOM 行数 ', @perf_bom_lines)
);

SELECT
  @perf_reprice_no AS reprice_no,
  @perf_object_count AS object_count,
  @perf_bom_lines AS bom_lines,
  @perf_seed_results AS seed_results;
