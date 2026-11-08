-- Forward, structure-only patch. Apply once after backup/preflight; never rewrite old results.
-- NULL customer identity denotes the still-readable original OA/product result model.
ALTER TABLE lp_monthly_reprice_batch
  ADD COLUMN scope_version INT NULL,
  ADD COLUMN source_count INT NULL,
  ADD COLUMN unresolved_source_count INT NULL,
  ADD CONSTRAINT ck_reprice_batch_source_counts CHECK (
    (scope_version IS NULL AND source_count IS NULL AND unresolved_source_count IS NULL)
    OR (scope_version IS NOT NULL AND scope_version=1 AND source_count IS NOT NULL AND source_count>0
      AND unresolved_source_count IS NOT NULL AND unresolved_source_count>=0
      AND unresolved_source_count<=source_count
      AND total_count>=0 AND total_count<=source_count-unresolved_source_count)),
  ADD UNIQUE KEY uk_reprice_batch_scope (id,reprice_no,business_unit_type,pricing_month);

ALTER TABLE lp_monthly_reprice_result
  ADD COLUMN reuse_batch_id BIGINT NULL,
  ADD COLUMN customer_identity_source VARCHAR(32) COLLATE utf8mb4_bin NULL,
  ADD COLUMN customer_identity_value VARCHAR(255) COLLATE utf8mb4_bin NULL,
  ADD COLUMN merged_source_count INT NULL,
  ADD COLUMN customer_object_scope VARBINARY(3072) GENERATED ALWAYS AS (
    CASE WHEN customer_identity_source IS NOT NULL THEN
      CAST(JSON_ARRAY(business_unit_type,pricing_month,product_code,
        customer_identity_source,customer_identity_value) AS CHAR CHARACTER SET utf8mb4)
    ELSE NULL END) STORED,
  ADD UNIQUE KEY uk_reprice_customer_product_month (customer_object_scope),
  ADD UNIQUE KEY uk_reprice_result_batch (id,reuse_batch_id),
  ADD CONSTRAINT fk_reprice_result_batch_scope FOREIGN KEY (reuse_batch_id,reprice_no,business_unit_type,pricing_month)
    REFERENCES lp_monthly_reprice_batch(id,reprice_no,business_unit_type,pricing_month),
  ADD CONSTRAINT ck_reprice_customer_identity CHECK (
    (customer_identity_source IS NULL AND customer_identity_value IS NULL
      AND merged_source_count IS NULL AND reuse_batch_id IS NULL)
    OR (customer_identity_source IS NOT NULL AND customer_identity_value IS NOT NULL
      AND customer_identity_source IN ('VERIFIED_CUSTOMER_CODE','OA_HEADER_CUSTOMER')
      AND CHAR_LENGTH(TRIM(customer_identity_value))>0
      AND BINARY customer_identity_value=BINARY TRIM(customer_identity_value)
      AND merged_source_count IS NOT NULL AND merged_source_count>0
      AND reuse_batch_id IS NOT NULL
      AND contract_version IS NOT NULL AND contract_version=1));

CREATE TABLE lp_monthly_reprice_source (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  batch_id BIGINT NOT NULL,
  object_id BIGINT NULL COMMENT 'NULL represents an individually traceable unresolved source',
  source_cost_version_id BIGINT NOT NULL,
  oa_no VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  oa_form_item_id BIGINT NOT NULL,
  issue_code VARCHAR(32) NULL,
  source_snapshot_json LONGTEXT NULL,
  CONSTRAINT ck_reprice_source_snapshot CHECK (source_snapshot_json IS NULL OR JSON_VALID(source_snapshot_json)),
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_reprice_source_version (batch_id,source_cost_version_id),
  UNIQUE KEY uk_reprice_source_object (batch_id,oa_no,oa_form_item_id),
  KEY idx_reprice_source_object (object_id,batch_id,id),
  KEY idx_reprice_source_oa (oa_no,oa_form_item_id,batch_id,object_id),
  CONSTRAINT fk_reprice_source_batch FOREIGN KEY (batch_id) REFERENCES lp_monthly_reprice_batch(id),
  CONSTRAINT fk_reprice_source_result FOREIGN KEY (object_id,batch_id) REFERENCES lp_monthly_reprice_result(id,reuse_batch_id),
  CONSTRAINT fk_reprice_source_version FOREIGN KEY (source_cost_version_id) REFERENCES lp_quote_cost_run_version(id),
  CONSTRAINT ck_reprice_source_resolution CHECK (
    (object_id IS NOT NULL AND issue_code IS NULL)
    OR (object_id IS NULL AND issue_code IS NOT NULL AND CHAR_LENGTH(TRIM(issue_code))>0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE lp_monthly_reprice_compute_group (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  batch_id BIGINT NOT NULL,
  product_code VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  input_schema_version INT NOT NULL,
  input_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  input_document LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  algorithm_version VARCHAR(64) NOT NULL,
  fee_policy_version VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  execution_no INT NOT NULL DEFAULT 1,
  attempt_count INT NOT NULL DEFAULT 0,
  worker_id VARCHAR(128) NULL,
  lease_expires_at DATETIME(6) NULL,
  result_document LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
  published_at DATETIME(6) NULL,
  error_message VARCHAR(1000) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  -- A digest is deliberately NOT unique: unequal full inputs may have the same digest.
  KEY idx_reprice_group_input (batch_id,input_digest,id),
  UNIQUE KEY uk_reprice_group_batch (id,batch_id),
  KEY idx_reprice_group_queue (batch_id,status,lease_expires_at,id),
  CONSTRAINT fk_reprice_group_batch FOREIGN KEY (batch_id) REFERENCES lp_monthly_reprice_batch(id),
  CONSTRAINT ck_reprice_group_input CHECK (
    input_schema_version=1 AND JSON_VALID(input_document)
    AND (JSON_TYPE(input_document)='OBJECT'
      AND JSON_TYPE(JSON_EXTRACT(input_document,'$.schemaVersion'))='INTEGER'
      AND JSON_EXTRACT(input_document,'$.schemaVersion')=input_schema_version) IS TRUE
    AND execution_no>0 AND attempt_count>=0),
  CONSTRAINT ck_reprice_group_state CHECK (
    status IN ('PENDING','RUNNING','SUCCESS','FAILED')
    AND ((status='RUNNING' AND worker_id IS NOT NULL AND lease_expires_at IS NOT NULL)
      OR (status<>'RUNNING' AND worker_id IS NULL AND lease_expires_at IS NULL))
    AND ((status='SUCCESS' AND result_document IS NOT NULL AND JSON_VALID(result_document) AND JSON_TYPE(result_document)='OBJECT' AND published_at IS NOT NULL)
      OR (status<>'SUCCESS' AND result_document IS NULL AND published_at IS NULL)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

ALTER TABLE lp_monthly_reprice_result
  ADD COLUMN compute_group_id BIGINT NULL,
  ADD KEY idx_reprice_result_group (compute_group_id,reuse_batch_id,id),
  ADD CONSTRAINT fk_reprice_result_group FOREIGN KEY (compute_group_id,reuse_batch_id)
    REFERENCES lp_monthly_reprice_compute_group(id,batch_id),
  ADD CONSTRAINT ck_reprice_result_group_scope CHECK (compute_group_id IS NULL OR reuse_batch_id IS NOT NULL);
