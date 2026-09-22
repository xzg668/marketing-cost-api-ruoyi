-- 价格办理按缺价料号唯一，不按成品、报价、月份或补录人员拆成多份。
-- 单位、组织及业务单元是这份来源的适用条件，不是允许另一人重复建来源的键。
CREATE TABLE IF NOT EXISTS lp_quote_tech_price_claim (
  material_code VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  owner_module_id BIGINT NOT NULL,
  organization_code VARCHAR(32) NOT NULL,
  business_unit_type VARCHAR(32) NOT NULL,
  price_unit VARCHAR(32) NOT NULL,
  currency VARCHAR(8) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (material_code),
  KEY idx_tech_price_claim_owner (owner_module_id),
  CONSTRAINT fk_tech_price_claim_owner FOREIGN KEY (owner_module_id) REFERENCES lp_quote_tech_module(id)
) ENGINE=InnoDB COMMENT='缺价料号唯一办理来源；价格金额仍保存在既有价格表';
