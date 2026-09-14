-- =============================================================================
-- V243: 核算明细唯一性增加 BOM 路径维度
-- -----------------------------------------------------------------------------
-- 正式 U9 BOM 仍会在应用层按料号汇总；电子图库见机表要求保留不同部品分支中
-- 同一采购料号的独立用量行。path 是冻结有效 BOM 中的稳定结构身份，因此将其
-- 纳入唯一键，既允许分支明细并存，又继续阻止同一路径重复发布。
-- =============================================================================

ALTER TABLE lp_bom_costing_row
  DROP INDEX uk_bom_costing_item_material_version;

ALTER TABLE lp_bom_costing_row
  ADD UNIQUE KEY uk_bom_costing_item_material_version (
    oa_no,
    oa_form_item_id,
    top_product_code,
    material_code,
    path,
    as_of_date,
    raw_version_effective_from
  );
