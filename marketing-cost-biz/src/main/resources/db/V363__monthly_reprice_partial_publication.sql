-- Structure only, apply once after backup/preflight. No cost, source or confirmation backfill.
-- The initial confirmation releases quotations permanently; repair progress is independent.
ALTER TABLE lp_monthly_reprice_batch
  ADD COLUMN published_revision BIGINT NULL COMMENT '最近确认发布的结果修订；NULL保留旧发布规则',
  MODIFY COLUMN active_lock_key VARCHAR(32) GENERATED ALWAYS AS
    (CASE WHEN (contract_version=1 AND confirmed_at IS NULL)
       OR (contract_version IS NULL AND status IN ('CREATED','PREPARING','RUNNING','WAIT_CONFIRM'))
      THEN business_unit_type ELSE NULL END) STORED COMMENT '尚未首次确认的业务单元锁键；已放行补算不重锁',
  ADD CONSTRAINT ck_reprice_published_revision CHECK (published_revision IS NULL OR
    (contract_version=1 AND confirmed_at IS NOT NULL AND confirmed_by IS NOT NULL
      AND published_revision>=0 AND published_revision<=result_revision));

ALTER TABLE lp_monthly_reprice_result
  ADD COLUMN published_cost_version_id BIGINT NULL COMMENT '客户产品最近确认的成本版本；补算时保留',
  ADD COLUMN published_source_cost_version_id BIGINT NULL COMMENT '已确认成本所采用的整份原报价版本',
  ADD CONSTRAINT fk_reprice_published_cost FOREIGN KEY (published_cost_version_id) REFERENCES lp_quote_cost_run_version(id),
  ADD CONSTRAINT fk_reprice_published_source FOREIGN KEY (published_source_cost_version_id) REFERENCES lp_quote_cost_run_version(id),
  ADD CONSTRAINT ck_reprice_published_pair CHECK (
    (published_cost_version_id IS NULL AND published_source_cost_version_id IS NULL) OR
    (published_cost_version_id IS NOT NULL AND published_cost_version_id>0
      AND published_source_cost_version_id IS NOT NULL AND published_source_cost_version_id>0));
