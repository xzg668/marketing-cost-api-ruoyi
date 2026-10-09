-- 未填写比例的供应关系也要保留；NULL 表示未填写，0 表示明确填写零比例。
ALTER TABLE lp_supplier_supply_ratio
  MODIFY COLUMN supply_ratio DECIMAL(18,6) NULL DEFAULT NULL COMMENT '供货比例，小数；NULL表示未填写';
