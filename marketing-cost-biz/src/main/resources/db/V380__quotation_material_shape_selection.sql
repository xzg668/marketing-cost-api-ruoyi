-- Per-quotation selection; original BOM and saved cost versions remain immutable.
CREATE TABLE IF NOT EXISTS lp_quote_material_shape_selection (
  id BIGINT NOT NULL AUTO_INCREMENT,
  business_unit_type VARCHAR(32) NOT NULL,
  oa_form_item_id BIGINT NOT NULL,
  period_month CHAR(7) NOT NULL,
  price_org_code VARCHAR(16) NOT NULL,
  material_code VARCHAR(64) NOT NULL,
  selected_shape VARCHAR(24) NULL,
  row_version BIGINT NOT NULL DEFAULT 1,
  updated_by VARCHAR(64) NOT NULL,
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY(id),
  UNIQUE KEY uk_quote_shape(business_unit_type,oa_form_item_id,period_month,price_org_code,material_code),
  CONSTRAINT ck_quote_shape CHECK(selected_shape IS NULL OR selected_shape IN ('MANUFACTURE','PURCHASE','OUTSOURCE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

UPDATE sys_menu SET menu_name='物料多形态属性价格表'
WHERE perms='price:material-sources:list';

-- Immutable evidence retained even after working settlement rows are rebuilt.
CREATE TABLE IF NOT EXISTS lp_quote_outsource_scrap_basis (
  costing_row_id BIGINT NOT NULL,
  business_unit_type VARCHAR(32) NOT NULL,
  oa_form_item_id BIGINT NOT NULL,
  period_month CHAR(7) NOT NULL,
  evidence_json JSON NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY(costing_row_id),
  KEY idx_quote_outsource_basis(business_unit_type,oa_form_item_id,period_month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
