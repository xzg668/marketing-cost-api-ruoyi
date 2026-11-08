-- Additive calculation namespace; no row updates or historical workflow replay.
ALTER TABLE lp_bom_costing_row
  ADD COLUMN calculation_scope VARCHAR(64) NOT NULL DEFAULT '' COMMENT '空为普通报价，调价为保存批次编号',
  DROP INDEX uk_bom_costing_item_material_version,
  ADD UNIQUE KEY uk_bom_costing_item_material_version
    (oa_no,oa_form_item_id,top_product_code,material_code,path,as_of_date,raw_version_effective_from,calculation_scope);
