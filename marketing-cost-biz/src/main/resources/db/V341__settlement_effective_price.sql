-- 原稿、基价发布时追加月度结算结果；历史原稿和核算快照保持不变。
CREATE TABLE IF NOT EXISTS lp_settlement_generation_scope (
  business_unit_type VARCHAR(32) NOT NULL PRIMARY KEY,
  CONSTRAINT chk_settlement_generation_scope CHECK (business_unit_type IN ('COMMERCIAL','HOUSEHOLD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='结算原稿与财务发布共享的事务锁';

CREATE TABLE IF NOT EXISTS lp_settlement_effective_price (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  business_unit_type VARCHAR(32) NOT NULL,
  material_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  price_type VARCHAR(24) NOT NULL,
  effective_month CHAR(7) NOT NULL,
  revision_no INT NOT NULL,
  rule_id BIGINT NOT NULL,
  rule_month CHAR(7) NOT NULL,
  rule_revision INT NOT NULL,
  finance_batch_id BIGINT NOT NULL DEFAULT 0,
  finance_month CHAR(7) NULL,
  finance_revision INT NULL,
  price_mode VARCHAR(24) NOT NULL,
  unit_price DECIMAL(24,12) NOT NULL,
  calculator_version VARCHAR(32) NOT NULL,
  input_fingerprint CHAR(64) NOT NULL,
  -- 保留十进制字面值，避免 MySQL JSON 数字转换为 double 丢失原稿/财务精度。
  calculation_json LONGTEXT NOT NULL,
  created_by VARCHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_settlement_effective_input (input_fingerprint),
  UNIQUE KEY uk_settlement_effective_revision (business_unit_type,material_code,price_type,effective_month,revision_no),
  KEY idx_settlement_effective_read (business_unit_type,rule_id,finance_batch_id,effective_month,revision_no),
  CONSTRAINT chk_settlement_effective_scope CHECK (business_unit_type IN ('COMMERCIAL','HOUSEHOLD')),
  CONSTRAINT chk_settlement_effective_month CHECK (effective_month REGEXP '^[0-9]{4}-(0[1-9]|1[0-2])$' AND rule_month<=effective_month),
  CONSTRAINT chk_settlement_effective_rule CHECK (rule_id>0 AND rule_revision>0 AND revision_no>0 AND price_type IN ('SETTLE_FIXED','SETTLE_LINKED')),
  CONSTRAINT chk_settlement_effective_price CHECK (unit_price>=0),
  CONSTRAINT chk_settlement_effective_basis CHECK (
    (price_mode IN ('FIXED_GIVEN','LINKED_GIVEN') AND finance_batch_id=0 AND finance_month IS NULL AND finance_revision IS NULL)
    OR (price_mode='LINKED_FORMULA' AND finance_batch_id>0 AND finance_month IS NOT NULL
      AND finance_month<=effective_month AND finance_revision IS NOT NULL AND finance_revision>0)),
  CONSTRAINT chk_settlement_effective_evidence CHECK (JSON_VALID(calculation_json) AND JSON_TYPE(calculation_json)='OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='发布时保存的结算月度有效价格及完整计算依据，仅追加修订';
