-- Nullable configuration only; existing rows and adopted prices are unchanged.
ALTER TABLE lp_supplier_supply_ratio
  ADD COLUMN selection_rule VARCHAR(255) NULL COMMENT '取价规则：无供货比例取最高价；空白沿用现有确认流程',
  ALGORITHM=INSTANT;
