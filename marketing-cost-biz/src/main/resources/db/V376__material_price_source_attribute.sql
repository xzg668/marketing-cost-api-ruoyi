-- 仅保存报价员维护的料号属性。来源和自制标记实时读取原算价记录，不复制价格。
CREATE TABLE IF NOT EXISTS lp_material_price_attribute (
  id BIGINT NOT NULL AUTO_INCREMENT,
  business_unit_type VARCHAR(32) NOT NULL,
  price_org_code VARCHAR(32) NOT NULL,
  material_code VARCHAR(64) NOT NULL,
  material_attribute VARCHAR(16) NULL COMMENT 'PURCHASE采购件；OUTSOURCE委外加工件；NULL未选择',
  row_version BIGINT NOT NULL DEFAULT 1,
  updated_by VARCHAR(64) NOT NULL,
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_material_price_attribute (business_unit_type, price_org_code, material_code),
  CONSTRAINT chk_material_price_attribute CHECK (material_attribute IS NULL OR material_attribute IN ('PURCHASE','OUTSOURCE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='价格来源汇总的报价员料号属性；暂不参与报价算法';
