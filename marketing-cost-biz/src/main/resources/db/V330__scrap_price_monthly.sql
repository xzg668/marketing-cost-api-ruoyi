-- 独立废料月价：SRM不维护此表；组织只属于基价来源，不属于废料报价唯一身份。
CREATE TABLE IF NOT EXISTS lp_scrap_price (
  id BIGINT NOT NULL AUTO_INCREMENT,
  business_unit_type VARCHAR(32) NOT NULL,
  price_month CHAR(7) NOT NULL,
  material_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  material_name VARCHAR(255) NOT NULL,
  specification VARCHAR(512) NOT NULL DEFAULT '',
  unit VARCHAR(32) NOT NULL DEFAULT 'KG',
  currency VARCHAR(8) NOT NULL DEFAULT 'CNY',
  tax_included TINYINT NOT NULL DEFAULT 0,
  supplier_code VARCHAR(64) COLLATE utf8mb4_bin NULL,
  supplier_name VARCHAR(255) COLLATE utf8mb4_bin NULL,
  supplier_key CHAR(64) GENERATED ALWAYS AS (
    SHA2(CASE
      WHEN NULLIF(TRIM(supplier_code), '') IS NOT NULL THEN CONCAT('CODE:', TRIM(supplier_code))
      WHEN NULLIF(TRIM(supplier_name), '') IS NOT NULL THEN CONCAT('NAME:', TRIM(supplier_name))
      ELSE 'COMMON' END, 256)
  ) STORED,
  price_type VARCHAR(16) NOT NULL,
  fixed_price DECIMAL(38,18) NULL,
  source_formula TEXT NULL,
  expression TEXT NULL,
  factor_bindings_json LONGTEXT NOT NULL,
  origin_type VARCHAR(24) NOT NULL,
  superseded_identity_keys_json LONGTEXT NULL COMMENT '编辑供应商替代的旧身份，不再从更早月份沿用',
  rule_source_month CHAR(7) NOT NULL,
  source_rule_id BIGINT NULL,
  source_rule_revision INT NULL,
  rule_revision INT NOT NULL DEFAULT 1,
  row_version BIGINT NOT NULL DEFAULT 1,
  content_hash CHAR(64) NOT NULL,
  unit_price DECIMAL(38,18) NULL,
  calc_status VARCHAR(24) NOT NULL DEFAULT 'WAITING_BASE',
  calc_message VARCHAR(1024) NULL,
  finance_org_code VARCHAR(32) NOT NULL DEFAULT '210',
  finance_month CHAR(7) NULL,
  finance_batch_id BIGINT NULL,
  finance_revision INT NULL,
  scheme_code VARCHAR(64) NULL,
  calculation_json LONGTEXT NULL,
  input_fingerprint CHAR(64) NULL,
  calculated_at DATETIME(6) NULL,
  import_batch_no VARCHAR(64) NULL,
  source_file_name VARCHAR(255) NULL,
  source_file_sha256 CHAR(64) NULL,
  source_sheet VARCHAR(128) NULL,
  source_row INT NULL,
  source_json LONGTEXT NULL,
  imported_by VARCHAR(64) NULL,
  imported_at DATETIME(6) NULL,
  created_by VARCHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_by VARCHAR(64) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_scrap_price_identity (business_unit_type, price_month, material_code, supplier_key),
  KEY idx_scrap_rule_month (business_unit_type, material_code, origin_type, price_month),
  KEY idx_scrap_rule_source (source_rule_id),
  CONSTRAINT fk_scrap_rule_source FOREIGN KEY (source_rule_id) REFERENCES lp_scrap_price(id),
  CONSTRAINT chk_scrap_business_unit CHECK (business_unit_type IN ('COMMERCIAL', 'HOUSEHOLD')),
  CONSTRAINT chk_scrap_month CHECK (price_month REGEXP '^[0-9]{4}-(0[1-9]|1[0-2])$'),
  CONSTRAINT chk_scrap_source_month CHECK (rule_source_month REGEXP '^[0-9]{4}-(0[1-9]|1[0-2])$'),
  CONSTRAINT chk_scrap_material CHECK (CHAR_LENGTH(TRIM(material_code)) > 0),
  CONSTRAINT chk_scrap_price_type CHECK (
    (price_type = 'FIXED' AND fixed_price IS NOT NULL AND fixed_price >= 0 AND expression IS NULL)
    OR (price_type = 'LINKED' AND fixed_price IS NULL AND expression IS NOT NULL
        AND CHAR_LENGTH(TRIM(expression)) > 0)),
  CONSTRAINT chk_scrap_origin CHECK (
    (origin_type IN ('IMPORT', 'MANUAL') AND rule_source_month = price_month AND source_rule_id IS NULL)
    OR (origin_type = 'CARRY_FORWARD' AND rule_source_month < price_month
        AND source_rule_id IS NOT NULL AND source_rule_revision IS NOT NULL AND source_rule_revision > 0)),
  CONSTRAINT chk_scrap_revision CHECK (rule_revision > 0 AND row_version > 0),
  CONSTRAINT chk_scrap_status CHECK (calc_status IN ('READY', 'WAITING_BASE', 'ERROR')),
  CONSTRAINT chk_scrap_result CHECK (
    (calc_status = 'READY' AND unit_price IS NOT NULL AND unit_price >= 0)
    OR (calc_status <> 'READY' AND unit_price IS NULL)),
  CONSTRAINT chk_scrap_unit CHECK (unit = 'KG' AND currency = 'CNY' AND tax_included = 0),
  CONSTRAINT chk_scrap_factor_org CHECK (finance_org_code = '210'),
  CONSTRAINT chk_scrap_bindings CHECK (JSON_VALID(factor_bindings_json)),
  CONSTRAINT chk_scrap_superseded CHECK (superseded_identity_keys_json IS NULL OR (JSON_VALID(superseded_identity_keys_json) AND JSON_TYPE(CAST(superseded_identity_keys_json AS JSON)) = 'ARRAY')),
  CONSTRAINT chk_scrap_calculation CHECK (calculation_json IS NULL OR JSON_VALID(calculation_json)),
  CONSTRAINT chk_scrap_source CHECK (source_json IS NULL OR JSON_VALID(source_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='跨组织共用的废料规则及月度有效价';
