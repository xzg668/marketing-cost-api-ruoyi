-- 结算价格与结算铜锌基价独立保存。旧表只保留历史，不迁入当前来源。
-- 组织是消费者，不是价格身份；每次修订追加记录，历史引用不随维护改变。
CREATE TABLE IF NOT EXISTS lp_settlement_price (
  id BIGINT NOT NULL AUTO_INCREMENT,
  business_unit_type VARCHAR(32) NOT NULL,
  material_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  material_name VARCHAR(255) NOT NULL,
  spec_model VARCHAR(512) NOT NULL DEFAULT '',
  price_type VARCHAR(24) NOT NULL,
  price_month CHAR(7) NOT NULL,
  revision_no INT NOT NULL,
  active TINYINT NOT NULL DEFAULT 1,
  given_price DECIMAL(38,18) NOT NULL,
  planned_price DECIMAL(38,18) NULL,
  markup_ratio DECIMAL(24,12) NULL,
  base_settle_price DECIMAL(38,18) NULL,
  budget_cu DECIMAL(24,12) NULL,
  budget_zn DECIMAL(24,12) NULL,
  reference_cu DECIMAL(24,12) NULL,
  reference_zn DECIMAL(24,12) NULL,
  original_copper_weight_g DECIMAL(24,12) NULL,
  original_zinc_weight_g DECIMAL(24,12) NULL,
  copper_weight_g DECIMAL(24,12) NULL,
  zinc_weight_g DECIMAL(24,12) NULL,
  weights_confirmed TINYINT NOT NULL DEFAULT 0,
  weight_confirmation_note VARCHAR(1024) NULL,
  weights_confirmed_by VARCHAR(64) NULL,
  weights_confirmed_at DATETIME(6) NULL,
  expression TEXT NULL,
  formula_version VARCHAR(32) NULL,
  tax_divisor DECIMAL(24,12) NULL,
  material_unit VARCHAR(32) NULL,
  unit_basis VARCHAR(32) NOT NULL DEFAULT 'BOM_MATERIAL_UNIT',
  source_system VARCHAR(32) NOT NULL,
  source_file_name VARCHAR(255) NULL,
  source_file_sha256 CHAR(64) NULL,
  source_file_ref VARCHAR(512) NULL,
  source_sheet VARCHAR(128) NULL,
  source_rows_json JSON NOT NULL,
  source_formula TEXT NULL,
  remark TEXT NULL,
  content_hash CHAR(64) NOT NULL,
  created_by VARCHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_settlement_price_revision (business_unit_type, material_code, price_type, price_month, revision_no),
  KEY idx_settlement_price_effective (business_unit_type, material_code, price_type, active, price_month, revision_no),
  KEY idx_settlement_price_list (business_unit_type, price_type, price_month, active),
  CONSTRAINT chk_settlement_price_identity CHECK (
    business_unit_type IN ('COMMERCIAL','HOUSEHOLD')
    AND OCTET_LENGTH(material_code) = OCTET_LENGTH(TRIM(material_code))
    AND CHAR_LENGTH(material_code) > 0 AND CHAR_LENGTH(TRIM(material_name)) > 0),
  CONSTRAINT chk_settlement_price_month CHECK (price_month REGEXP '^[0-9]{4}-(0[1-9]|1[0-2])$'),
  CONSTRAINT chk_settlement_price_revision CHECK (revision_no > 0 AND active IN (0,1)),
  CONSTRAINT chk_settlement_price_given CHECK (given_price >= 0),
  CONSTRAINT chk_settlement_price_formula CHECK (
    price_type = 'SETTLE_FIXED' OR (price_type = 'SETTLE_LINKED'
      AND planned_price IS NOT NULL AND planned_price >= 0
      AND markup_ratio IS NOT NULL AND markup_ratio > 0
      AND budget_cu IS NOT NULL AND budget_cu >= 0 AND budget_zn IS NOT NULL AND budget_zn >= 0
      AND tax_divisor IS NOT NULL AND tax_divisor > 0
      AND expression IS NOT NULL AND CHAR_LENGTH(TRIM(expression)) > 0
      AND formula_version IS NOT NULL AND CHAR_LENGTH(TRIM(formula_version)) > 0)),
  CONSTRAINT chk_settlement_price_weights CHECK (
    (original_copper_weight_g IS NULL OR original_copper_weight_g >= 0)
    AND (original_zinc_weight_g IS NULL OR original_zinc_weight_g >= 0)
    AND (copper_weight_g IS NULL OR copper_weight_g >= 0)
    AND (zinc_weight_g IS NULL OR zinc_weight_g >= 0)
    AND (weights_confirmed = 0 OR (weights_confirmed = 1
      AND copper_weight_g IS NOT NULL AND zinc_weight_g IS NOT NULL
      AND weight_confirmation_note IS NOT NULL AND CHAR_LENGTH(TRIM(weight_confirmation_note)) > 0
      AND weights_confirmed_by IS NOT NULL AND weights_confirmed_at IS NOT NULL))),
  CONSTRAINT chk_settlement_price_unit CHECK (unit_basis = 'BOM_MATERIAL_UNIT'),
  CONSTRAINT chk_settlement_price_source CHECK (JSON_TYPE(source_rows_json) = 'ARRAY')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='商用与板换共用的结算固定/联动价格修订；与SRM采购价分离';

CREATE TABLE IF NOT EXISTS lp_settlement_base_price (
  id BIGINT NOT NULL AUTO_INCREMENT,
  business_unit_type VARCHAR(32) NOT NULL,
  price_month CHAR(7) NOT NULL,
  revision_no INT NOT NULL,
  active TINYINT NOT NULL DEFAULT 1,
  copper_price DECIMAL(24,12) NOT NULL,
  zinc_price DECIMAL(24,12) NOT NULL,
  unit VARCHAR(16) NOT NULL DEFAULT 'CNY/KG',
  tax_basis VARCHAR(24) NOT NULL DEFAULT 'BEFORE_FORMULA_TAX',
  source_kind VARCHAR(24) NOT NULL,
  source_file_sha256 CHAR(64) NULL,
  change_reason VARCHAR(1024) NOT NULL,
  created_by VARCHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_settlement_base_revision (business_unit_type, price_month, revision_no),
  KEY idx_settlement_base_effective (business_unit_type, active, price_month, revision_no),
  CONSTRAINT chk_settlement_base_unit_scope CHECK (business_unit_type IN ('COMMERCIAL','HOUSEHOLD')),
  CONSTRAINT chk_settlement_base_month CHECK (price_month REGEXP '^[0-9]{4}-(0[1-9]|1[0-2])$'),
  CONSTRAINT chk_settlement_base_revision CHECK (revision_no > 0 AND active IN (0,1)),
  CONSTRAINT chk_settlement_base_prices CHECK (copper_price >= 0 AND zinc_price >= 0),
  CONSTRAINT chk_settlement_base_units CHECK (unit = 'CNY/KG' AND tax_basis = 'BEFORE_FORMULA_TAX'),
  CONSTRAINT chk_settlement_base_source CHECK (source_kind IN ('IMPORT_REFERENCE','FINANCE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='独立结算铜锌月度成组基价；公式内税除数只执行一次';
