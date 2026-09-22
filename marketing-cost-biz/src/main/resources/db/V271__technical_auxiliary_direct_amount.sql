-- CMS 科目金额与上传分摊费用直接保存；旧用量、单价字段只供历史版本读取。
-- 不重写历史明细，不伪造料号、单位或单价。空金额仅允许作为未完整的草稿。
ALTER TABLE lp_quote_tech_aux_item
  MODIFY subject_code VARCHAR(64) NULL COMMENT 'CMS 科目编码；上传明细待财务归类',
  MODIFY auxiliary_material_no VARCHAR(64) NULL COMMENT '上传原表料号；CMS 科目无料号',
  MODIFY quantity DECIMAL(20,8) NULL,
  MODIFY original_unit VARCHAR(32) NULL,
  MODIFY standard_quantity DECIMAL(20,8) NULL,
  MODIFY standard_unit VARCHAR(32) NULL,
  MODIFY conversion_factor DECIMAL(20,8) NULL DEFAULT 1,
  MODIFY reference_unit_price DECIMAL(20,8) NULL,
  MODIFY price_unit VARCHAR(32) NULL,
  MODIFY loss_rate DECIMAL(12,8) NULL DEFAULT 0,
  MODIFY amount DECIMAL(20,8) NULL COMMENT '本次辅料金额，元/只；空值为未填';

ALTER TABLE lp_quote_tech_module
  DROP CHECK ck_quote_tech_module_entry_mode,
  ADD CONSTRAINT ck_quote_tech_module_entry_mode CHECK (entry_mode IN ('NONE','REFERENCE','MANUAL','UPLOAD'));
