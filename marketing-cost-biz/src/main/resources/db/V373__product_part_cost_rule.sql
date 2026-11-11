-- Business configuration only. Saved costing versions carry their own immutable rule snapshot.
CREATE TABLE lp_product_part_cost_rule (
  id BIGINT NOT NULL AUTO_INCREMENT,
  business_unit_type VARCHAR(32) NOT NULL,
  product_code VARCHAR(80) COLLATE utf8mb4_bin NOT NULL,
  product_name VARCHAR(255) NULL,
  product_spec VARCHAR(255) NULL,
  product_model VARCHAR(255) NULL,
  part_code VARCHAR(80) COLLATE utf8mb4_bin NULL,
  part_name VARCHAR(255) NULL,
  part_model VARCHAR(255) COLLATE utf8mb4_bin NULL,
  shape VARCHAR(32) NOT NULL,
  manufacture_rate DECIMAL(18,12) NOT NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  model_only VARCHAR(255) COLLATE utf8mb4_bin GENERATED ALWAYS AS (IF(part_code IS NULL,part_model,NULL)) STORED,
  PRIMARY KEY(id),
  UNIQUE KEY uk_product_part_cost_code(business_unit_type,product_code,part_code),
  UNIQUE KEY uk_product_part_cost_model(business_unit_type,product_code,model_only),
  CONSTRAINT chk_product_part_cost_key CHECK (CHAR_LENGTH(TRIM(product_code))>0 AND
    (CHAR_LENGTH(TRIM(COALESCE(part_code,'')))>0 OR CHAR_LENGTH(TRIM(COALESCE(part_model,'')))>0)),
  CONSTRAINT chk_product_part_cost_shape CHECK (shape IN ('采购件','制造件')),
  CONSTRAINT chk_product_part_cost_rate CHECK (manufacture_rate>=0 AND manufacture_rate<1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
