-- 仅用于验证旧库升级和历史数据保留，不属于正式初始化结构。
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
