CREATE TABLE lp_supplier_price_decision (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  scope_key CHAR(64) NOT NULL,
  fingerprint CHAR(64) NOT NULL,
  business_unit_type VARCHAR(32) NOT NULL,
  oa_no VARCHAR(128) NOT NULL,
  oa_form_item_id BIGINT NOT NULL,
  period_month VARCHAR(7) NOT NULL,
  pricing_date DATE NOT NULL,
  org_code VARCHAR(64) NOT NULL,
  material_code VARCHAR(128) NOT NULL,
  price_type VARCHAR(32) NOT NULL,
  decision VARCHAR(32) NOT NULL COMMENT 'WAIT_IMPORT 有新增审核 / FALLBACK_HIGH 无新增审核',
  confirmed_by VARCHAR(128) NOT NULL,
  confirmed_at DATETIME NOT NULL,
  retry_status VARCHAR(32) NOT NULL DEFAULT 'WAITING',
  retry_message VARCHAR(1000) DEFAULT NULL,
  KEY idx_supplier_decision_scope (scope_key, id),
  KEY idx_supplier_decision_wait (business_unit_type, period_month, decision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通用供货比率新增审核确认记录';

ALTER TABLE lp_price_prepare_batch
  ADD COLUMN supplier_price_reviews JSON DEFAULT NULL COMMENT '本批次供货比率取价确认快照';
