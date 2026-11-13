-- V376已应用；本次只向前扩展来源粒度，保留原人工选择（空来源表示旧公共选择）。
ALTER TABLE lp_material_price_attribute
  ADD COLUMN price_month CHAR(7) NOT NULL DEFAULT '' AFTER business_unit_type,
  ADD COLUMN price_source_type VARCHAR(32) NOT NULL DEFAULT '' AFTER material_code,
  DROP INDEX uk_material_price_attribute,
  ADD UNIQUE KEY uk_material_price_attribute (business_unit_type,price_month,price_org_code,material_code,price_source_type),
  DROP CHECK chk_material_price_attribute,
  ADD CONSTRAINT chk_material_price_attribute CHECK (material_attribute IS NULL OR material_attribute IN ('MANUFACTURE','PURCHASE','OUTSOURCE'));

CREATE TABLE lp_material_price_source_scope (
  business_unit_type VARCHAR(32) NOT NULL,
  price_month CHAR(7) NOT NULL,
  generation BIGINT NOT NULL DEFAULT 0,
  refreshed_at DATETIME(6) NULL,
  refreshed_by VARCHAR(64) NULL,
  material_count INT NOT NULL DEFAULT 0,
  source_count INT NOT NULL DEFAULT 0,
  PRIMARY KEY (business_unit_type,price_month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='多来源清单生成批次及同范围刷新锁';

CREATE TABLE lp_material_price_source_row (
  id BIGINT NOT NULL AUTO_INCREMENT,
  business_unit_type VARCHAR(32) NOT NULL,
  price_month CHAR(7) NOT NULL,
  price_org_code VARCHAR(32) NOT NULL,
  organization_name VARCHAR(128) NOT NULL,
  material_code VARCHAR(64) NOT NULL,
  material_name VARCHAR(255) NULL,
  material_model VARCHAR(255) NULL,
  material_spec VARCHAR(512) NULL,
  price_source_type VARCHAR(32) NOT NULL,
  prices_json LONGTEXT NOT NULL,
  auto_attribute VARCHAR(16) NULL,
  generation BIGINT NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_material_price_source_row (business_unit_type,price_month,price_org_code,material_code,price_source_type),
  CONSTRAINT chk_material_source_prices CHECK (JSON_VALID(prices_json) AND JSON_TYPE(CAST(prices_json AS JSON))='ARRAY'),
  CONSTRAINT chk_material_source_auto CHECK (auto_attribute IS NULL OR auto_attribute='MANUFACTURE')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='每日04点或财务手动更新的多来源价格清单；尚不参与报价算法';
