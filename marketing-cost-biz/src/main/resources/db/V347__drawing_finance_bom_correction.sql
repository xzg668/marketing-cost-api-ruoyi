-- 本单图库 BOM 修正版：原始来源版本保持，修正数量按料品单位计量。
ALTER TABLE lp_electronic_drawing_source_node
  ADD COLUMN quantity_unit VARCHAR(32) NULL COMMENT '财务修正用量单位；原图库件数为空',
  ADD COLUMN corrected_from_source_node_id BIGINT NULL COMMENT '修正版对应上一版源节点ID';
