-- =============================================================================
-- V129: 月度调价性能索引
-- -----------------------------------------------------------------------------
-- 目标：
--   1. 支撑 Worker 大批量领取 PENDING / 过期 RUNNING 任务。
--   2. 支撑月度调价结果页、任务页、明细下钻在 1 万到 10 万对象规模下稳定分页。
--   3. 只补索引，不改历史数据，不影响 lp_cost_run_result 等日常 OA 结果表。
-- =============================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS add_index_if_not_exists_v129;
DELIMITER //
CREATE PROCEDURE add_index_if_not_exists_v129(
  IN p_table_name VARCHAR(64),
  IN p_index_name VARCHAR(64),
  IN p_index_def TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1
      FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
  ) AND NOT EXISTS (
    SELECT 1
      FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND INDEX_NAME = p_index_name
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table_name, '` ADD ', p_index_def);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END//
DELIMITER ;

CALL add_index_if_not_exists_v129('lp_monthly_reprice_task', 'idx_reprice_task_claim_perf',
  'KEY idx_reprice_task_claim_perf (status, lock_expire_time, id)');

CALL add_index_if_not_exists_v129('lp_monthly_reprice_task', 'idx_reprice_task_page',
  'KEY idx_reprice_task_page (reprice_no, id)');

CALL add_index_if_not_exists_v129('lp_monthly_reprice_task', 'idx_reprice_task_status_page',
  'KEY idx_reprice_task_status_page (reprice_no, status, id)');

CALL add_index_if_not_exists_v129('lp_monthly_reprice_result', 'idx_reprice_result_page',
  'KEY idx_reprice_result_page (reprice_no, id)');

CALL add_index_if_not_exists_v129('lp_monthly_reprice_result', 'idx_reprice_result_status_page',
  'KEY idx_reprice_result_status_page (reprice_no, calc_status, id)');

CALL add_index_if_not_exists_v129('lp_monthly_reprice_result', 'idx_reprice_result_product_page',
  'KEY idx_reprice_result_product_page (reprice_no, product_code, id)');

CALL add_index_if_not_exists_v129('lp_monthly_reprice_part_item', 'idx_monthly_part_drilldown',
  'KEY idx_monthly_part_drilldown (reprice_no, calc_object_key, line_no, id)');

CALL add_index_if_not_exists_v129('lp_monthly_reprice_cost_item', 'idx_monthly_cost_drilldown',
  'KEY idx_monthly_cost_drilldown (reprice_no, calc_object_key, line_no, id)');

CALL add_index_if_not_exists_v129('lp_monthly_reprice_audit_log', 'idx_monthly_audit_page',
  'KEY idx_monthly_audit_page (reprice_no, operation_time, id)');

DROP PROCEDURE IF EXISTS add_index_if_not_exists_v129;
