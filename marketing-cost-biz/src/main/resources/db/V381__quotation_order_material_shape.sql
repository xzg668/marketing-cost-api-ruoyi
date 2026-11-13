-- Order-wide decisions. Legacy item selections and saved costs are retained.
CREATE TABLE IF NOT EXISTS lp_quote_order_material_shape (
  id BIGINT NOT NULL AUTO_INCREMENT,
  business_unit_type VARCHAR(32) NOT NULL,
  oa_form_id BIGINT NOT NULL,
  period_month CHAR(7) NOT NULL,
  price_org_code VARCHAR(16) NOT NULL,
  material_code VARCHAR(64) NOT NULL,
  selected_shape VARCHAR(24) NULL,
  row_version BIGINT NOT NULL DEFAULT 1,
  updated_by VARCHAR(64) NOT NULL,
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY(id),
  UNIQUE KEY uk_order_shape(business_unit_type,oa_form_id,period_month,price_org_code,material_code),
  CONSTRAINT ck_order_shape CHECK(selected_shape IS NULL OR selected_shape IN ('MANUFACTURE','PURCHASE','OUTSOURCE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
