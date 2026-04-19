-- V2: 补充关键查询索引，优化价格匹配与BOM查询性能

-- 联动价按物料编码查询
ALTER TABLE lp_price_linked_item ADD INDEX idx_linked_material (material_code);

-- 固定价按物料编码查询
ALTER TABLE lp_price_fixed_item ADD INDEX idx_fixed_material (material_code);

-- 区间价按物料编码查询
ALTER TABLE lp_price_range_item ADD INDEX idx_range_material (material_code);

-- 结算价按物料编码查询
ALTER TABLE lp_price_settle_item ADD INDEX idx_settle_material (material_code);

-- 物料价格类型对照
ALTER TABLE lp_material_price_type ADD INDEX idx_mpt_material (material_code);

-- BOM管理按OA单号查询
ALTER TABLE lp_bom_manage_item ADD INDEX idx_bom_manage_oa (oa_no);

-- BOM手工维护按父件编码查询
ALTER TABLE lp_bom_manual_item ADD INDEX idx_bom_manual_parent (parent_code);
