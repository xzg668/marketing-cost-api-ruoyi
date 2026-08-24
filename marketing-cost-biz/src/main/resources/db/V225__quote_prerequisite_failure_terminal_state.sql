-- 报价批次前置同步失败必须是可见、可重试的终态，不能永久停在 PENDING/0%。
UPDATE lp_cost_run_task t
JOIN lp_cost_run_batch b
  ON b.batch_no = t.batch_no
 AND b.execution_no = t.execution_no
 AND b.scene = 'QUOTE'
 AND b.prerequisite_status = 'FAILED'
   SET t.status = 'FAILED',
       t.progress = 100,
       t.worker_id = NULL,
       t.locked_at = NULL,
       t.lock_expire_time = NULL,
       t.error_message = COALESCE(b.error_message, '报价批次前置同步失败'),
       t.error_stack = NULL,
       t.finished_at = COALESCE(t.finished_at, b.updated_at, NOW()),
       t.updated_at = NOW()
 WHERE t.status IN ('PENDING', 'RETRYABLE');

UPDATE lp_cost_run_batch b
   SET b.status = 'FAILED',
       b.failed_count = (
         SELECT COUNT(*)
           FROM lp_cost_run_task t
          WHERE t.batch_no = b.batch_no
            AND t.execution_no = b.execution_no
            AND t.status = 'FAILED'
       ),
       b.progress = 100,
       b.finished_at = COALESCE(b.finished_at, b.updated_at, NOW()),
       b.updated_at = NOW()
 WHERE b.scene = 'QUOTE'
   AND b.prerequisite_status = 'FAILED'
   AND b.status IN ('PENDING', 'RUNNING', 'PARTIAL_FAILED');
