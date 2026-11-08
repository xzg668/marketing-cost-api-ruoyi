-- Structure only. Legacy rows retain NULL contract_version and their original values.
-- Apply once after identity/backup/preflight; do not replay historical migrations.
ALTER TABLE lp_monthly_reprice_batch
  ADD COLUMN contract_version INT NULL COMMENT 'NULL历史批次；1为1130契约',
  ADD COLUMN go_live_month CHAR(7) NULL COMMENT '经核定的实际上线月份',
  ADD COLUMN result_revision BIGINT NULL COMMENT '任务/结果集合修订号',
  ADD COLUMN checked_revision BIGINT NULL COMMENT '检查针对的结果修订号',
  ADD COLUMN check_status VARCHAR(16) NULL COMMENT 'PENDING/PASSED/FAILED',
  ADD COLUMN check_result_json JSON NULL COMMENT '保存的检查结果',
  ADD COLUMN checked_at DATETIME(6) NULL,
  ADD COLUMN contract_month_key VARCHAR(40) GENERATED ALWAYS AS
    (CASE WHEN contract_version=1 THEN CONCAT(business_unit_type, ':', pricing_month) ELSE NULL END) STORED,
  ADD UNIQUE KEY uk_reprice_1130_month (contract_month_key),
  MODIFY COLUMN active_lock_key VARCHAR(32) GENERATED ALWAYS AS
    (CASE WHEN (contract_version=1 AND status<>'CONFIRMED')
       OR (contract_version IS NULL AND status IN ('CREATED','PREPARING','RUNNING','WAIT_CONFIRM'))
      THEN business_unit_type ELSE NULL END) STORED COMMENT '未确认批次业务单元锁键（1130失败仍保持）',
  ADD CONSTRAINT ck_reprice_1130_batch CHECK (contract_version IS NULL OR
    (contract_version=1 AND business_unit_type IN ('COMMERCIAL','HOUSEHOLD')
      AND go_live_month IS NOT NULL AND go_live_month<=pricing_month
      AND pricing_month REGEXP '^[0-9]{4}-(0[1-9]|1[0-2])$'
      AND go_live_month REGEXP '^[0-9]{4}-(0[1-9]|1[0-2])$'
      AND result_revision IS NOT NULL AND result_revision>=0
      AND status IN ('QUEUED','RUNNING','CHECKING','WAIT_CONFIRM','FAILED','CONFIRMED')
      AND check_status IS NOT NULL AND check_status IN ('PENDING','PASSED','FAILED')
      AND (status NOT IN ('WAIT_CONFIRM','CONFIRMED') OR
        (check_status='PASSED' AND checked_revision IS NOT NULL
         AND checked_revision=result_revision AND checked_at IS NOT NULL AND check_result_json IS NOT NULL))
      AND (status<>'CONFIRMED' OR (confirmed_at IS NOT NULL AND confirmed_by IS NOT NULL))));

ALTER TABLE lp_quote_cost_run_version
  ADD COLUMN reprice_no VARCHAR(64) NULL COMMENT '调价结果所有者；普通报价及历史版本为NULL',
  ADD KEY idx_cost_version_reprice (reprice_no, oa_form_item_id);

ALTER TABLE lp_monthly_reprice_result
  ADD COLUMN contract_version INT NULL COMMENT 'NULL历史结果；1为1130契约',
  ADD COLUMN cost_run_version_id BIGINT NULL COMMENT '本次成功核算版本；source_cost_version_id保留首次报价版本',
  ADD UNIQUE KEY uk_reprice_1130_result_version (cost_run_version_id),
  ADD CONSTRAINT fk_reprice_1130_result_version FOREIGN KEY (cost_run_version_id) REFERENCES lp_quote_cost_run_version(id),
  ADD CONSTRAINT ck_reprice_1130_result CHECK (contract_version IS NULL OR
    (contract_version=1 AND oa_form_item_id IS NOT NULL AND source_cost_version_id IS NOT NULL
      AND calc_status IN ('PENDING','SUCCESS')
      AND ((calc_status='PENDING' AND cost_run_version_id IS NULL)
        OR (calc_status='SUCCESS' AND cost_run_version_id IS NOT NULL AND total_cost IS NOT NULL))));
