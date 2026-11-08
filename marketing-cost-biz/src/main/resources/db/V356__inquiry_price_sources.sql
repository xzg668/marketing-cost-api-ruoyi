-- User inquiry originals and immutable formula versions; shared by consuming organizations 210/220.
CREATE TABLE lp_inquiry_import_batch (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  business_unit_type VARCHAR(32) NOT NULL,
  price_month CHAR(7) NOT NULL,
  request_id VARCHAR(36) NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  file_hash CHAR(64) NOT NULL,
  original_file MEDIUMBLOB NOT NULL,
  row_count INT NOT NULL,
  created_by VARCHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_inquiry_request (business_unit_type,request_id),
  KEY idx_inquiry_import_scope (business_unit_type,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE lp_inquiry_rule (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  batch_id BIGINT NOT NULL,
  business_unit_type VARCHAR(32) NOT NULL,
  price_month CHAR(7) NOT NULL,
  identity_key CHAR(64) NOT NULL,
  material_key VARCHAR(64) NOT NULL,
  specification_key VARCHAR(512) NOT NULL,
  rule_json JSON NOT NULL,
  KEY idx_inquiry_material (business_unit_type,material_key,price_month,batch_id),
  KEY idx_inquiry_specification (business_unit_type,specification_key,price_month,batch_id),
  KEY idx_inquiry_identity (business_unit_type,identity_key,batch_id),
  CONSTRAINT fk_inquiry_rule_batch FOREIGN KEY (batch_id) REFERENCES lp_inquiry_import_batch(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE lp_inquiry_effective_price (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  business_unit_type VARCHAR(32) NOT NULL,
  price_month CHAR(7) NOT NULL,
  rule_id BIGINT NOT NULL,
  finance_batch_id BIGINT NOT NULL DEFAULT 0,
  input_hash CHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  unit_price DECIMAL(38,18) NULL,
  message VARCHAR(1000) NULL,
  calculation_json JSON NOT NULL,
  trigger_kind VARCHAR(32) NOT NULL,
  created_by VARCHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_inquiry_effective_input (business_unit_type,price_month,rule_id,input_hash),
  KEY idx_inquiry_effective_month (business_unit_type,price_month,rule_id,id),
  CONSTRAINT fk_inquiry_effective_rule FOREIGN KEY (rule_id) REFERENCES lp_inquiry_rule(id),
  CONSTRAINT ck_inquiry_effective_amount CHECK (unit_price IS NULL OR unit_price>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
