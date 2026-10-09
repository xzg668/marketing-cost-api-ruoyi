-- 导入按物料整组切换当前记录；批次保留原比例，供历史核对。
ALTER TABLE lp_supplier_supply_ratio
  ADD COLUMN is_active TINYINT NOT NULL DEFAULT 1 COMMENT '是否当前有效：1有效，0历史失效' AFTER source_batch_no,
  DROP INDEX uk_supplier_ratio_biz,
  ADD UNIQUE KEY uk_supplier_ratio_batch
    (business_unit_type, material_code, source_batch_no, supplier_code),
  ADD KEY idx_supplier_ratio_active (business_unit_type, material_code, is_active, deleted);

UPDATE lp_supplier_supply_ratio SET is_active=0 WHERE deleted<>0;
