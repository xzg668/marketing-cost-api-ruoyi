-- Only additive structures. No source prices, monthly prices or business history are changed.
CREATE TABLE IF NOT EXISTS lp_monthly_price_import (
  id BIGINT NOT NULL AUTO_INCREMENT,
  business_unit_type VARCHAR(32) NOT NULL,
  pricing_month CHAR(7) NOT NULL,
  request_key VARCHAR(64) NOT NULL,
  status VARCHAR(24) NOT NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  result_json LONGTEXT NULL,
  created_by VARCHAR(64) NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_monthly_price_import_request (business_unit_type, request_key),
  KEY idx_monthly_price_import_scope (business_unit_type, pricing_month, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS lp_monthly_price_import_file (
  id BIGINT NOT NULL AUTO_INCREMENT,
  import_id BIGINT NOT NULL,
  relative_path VARCHAR(512) NOT NULL,
  path_hash CHAR(64) NOT NULL,
  file_sha256 CHAR(64) NULL,
  source_workbook LONGBLOB NULL,
  kind VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  parsed_json LONGTEXT NULL,
  publication_json LONGTEXT NULL,
  error_message TEXT NULL,
  supersedes_id BIGINT NULL,
  active_flag TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY idx_monthly_price_import_file (import_id, active_flag),
  KEY idx_monthly_price_import_path (import_id, path_hash),
  CONSTRAINT fk_monthly_price_import_file FOREIGN KEY (import_id) REFERENCES lp_monthly_price_import(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
