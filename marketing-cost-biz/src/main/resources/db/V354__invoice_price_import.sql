-- User-maintained invoice prices. Raw U9 snapshot V353 remains an independent integration source.
CREATE TABLE lp_invoice_import_batch (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  business_unit_type VARCHAR(32) NOT NULL,
  org_code VARCHAR(16) NOT NULL,
  request_id VARCHAR(36) NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  file_hash CHAR(64) NOT NULL,
  original_file MEDIUMBLOB NOT NULL,
  rows_json JSON NOT NULL,
  row_count INT NOT NULL,
  duplicate_count INT NOT NULL,
  created_by VARCHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_invoice_request (business_unit_type,request_id),
  KEY idx_invoice_import_scope (business_unit_type,org_code,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE lp_invoice_price (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  business_unit_type VARCHAR(32) NOT NULL,
  org_code VARCHAR(16) NOT NULL,
  material_code VARCHAR(64) NOT NULL,
  document_no VARCHAR(128) NOT NULL,
  supplier_code VARCHAR(64) NOT NULL,
  supplier_name VARCHAR(255) NOT NULL,
  material_name VARCHAR(255) NOT NULL,
  specification VARCHAR(512) NOT NULL,
  pricing_unit VARCHAR(32) NOT NULL,
  unit_price DECIMAL(38,18) NOT NULL,
  currency VARCHAR(16) NOT NULL,
  voucher_no VARCHAR(128) NOT NULL,
  business_type VARCHAR(128) NOT NULL,
  document_type VARCHAR(64) NOT NULL,
  price_type VARCHAR(64) NOT NULL,
  sheet_name VARCHAR(128) NOT NULL,
  source_row INT NOT NULL,
  batch_id BIGINT NOT NULL,
  UNIQUE KEY uk_invoice_identity (business_unit_type,org_code,material_code,document_no,supplier_code),
  KEY idx_invoice_material_batch (business_unit_type,material_code,batch_id),
  KEY idx_invoice_org_material (business_unit_type,org_code,material_code,batch_id),
  CONSTRAINT fk_invoice_batch FOREIGN KEY (batch_id) REFERENCES lp_invoice_import_batch(id),
  CONSTRAINT ck_invoice_price CHECK (unit_price>=0 AND source_row>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- Preserve all existing monthly rows and immutable-row triggers. A new source revision appends facts.
ALTER TABLE lp_material_monthly_price
  ADD COLUMN invoice_revision BIGINT NOT NULL DEFAULT 0,
  DROP INDEX uk_monthly_price_identity,
  ADD UNIQUE KEY uk_monthly_price_identity (family_hash,applicability_hash,invoice_revision);
