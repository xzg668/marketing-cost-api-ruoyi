-- 同业务单元、报价组织、料品组织、核算月、产品料号、主制造目的只允许一条 U9 首查结果。
-- 历史月度记录 identity_key 保持 NULL，不参与新唯一键，也不破坏既有报价追溯。
ALTER TABLE lp_quote_bom_monthly_snapshot
  ADD COLUMN business_unit_type VARCHAR(32) DEFAULT NULL
    COMMENT 'U9月度首查业务单元' AFTER price_org_code,
  ADD COLUMN material_organization_code VARCHAR(32) DEFAULT NULL
    COMMENT 'U9月度首查料品组织' AFTER business_unit_type,
  ADD COLUMN snapshot_identity_key CHAR(64) DEFAULT NULL
    COMMENT 'U9月度首查唯一身份SHA-256；历史/补录记录为空' AFTER material_organization_code,
  ADD COLUMN structure_fingerprint CHAR(64) DEFAULT NULL
    COMMENT 'U9首查BOM结构指纹' AFTER bom_batch_id,
  ADD COLUMN line_count INT NOT NULL DEFAULT 0
    COMMENT 'U9首查BOM节点数' AFTER structure_fingerprint,
  ADD UNIQUE KEY uk_quote_bom_u9_monthly_identity (snapshot_identity_key);
