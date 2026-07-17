-- 制造件存在多个直接材料时，每条材料必须使用自己的净重，不能重复使用父件理论总净重。
-- 关系键包含组织、父件、子料、BOM 版本和核算月份；BOM 版本/月为空时可作为长期默认记录。

CREATE TABLE IF NOT EXISTS lp_make_part_child_net_weight (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  material_organization_code VARCHAR(32) NOT NULL COMMENT '料品组织：COMMERCIAL/PLATE',
  parent_material_no VARCHAR(64) NOT NULL COMMENT '父制造件料号',
  child_material_no VARCHAR(64) NOT NULL COMMENT '直接子材料料号',
  bom_version VARCHAR(64) NOT NULL DEFAULT '' COMMENT 'U9 BOM版本，空表示通用',
  period_month VARCHAR(7) NOT NULL DEFAULT '' COMMENT '核算月份yyyy-MM，空表示长期有效',
  net_weight_g DECIMAL(20,8) NOT NULL COMMENT '该父件下该子材料净重(g)',
  source_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL' COMMENT '来源类型',
  source_reference VARCHAR(512) DEFAULT NULL COMMENT '来源文件或批次',
  remark VARCHAR(1000) DEFAULT NULL COMMENT '备注',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_make_part_child_net_weight (
    material_organization_code,
    parent_material_no,
    child_material_no,
    bom_version,
    period_month
  ),
  KEY idx_make_part_child_net_weight_lookup (
    material_organization_code,
    parent_material_no,
    child_material_no,
    period_month
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='制造件父子材料净重关系';

-- 见机表“制造件4”：1053000301687(A板片组件)含不锈钢与铜箔两条直接材料。
INSERT INTO lp_make_part_child_net_weight (
  material_organization_code,
  parent_material_no,
  child_material_no,
  bom_version,
  period_month,
  net_weight_g,
  source_type,
  source_reference,
  remark
) VALUES
  (
    'PLATE', '1053000301687', '301240299', 'F001', '2026-07', 37.89999570,
    'MACHINE_TABLE_EXCEL', '3 产品成本计算表--板换 （5.30-提供） 7.9改-siya.xls/制造件4',
    'A板片组件-不锈钢净重'
  ),
  (
    'PLATE', '1053000301687', '301070047', 'F001', '2026-07', 5.59999940,
    'MACHINE_TABLE_EXCEL', '3 产品成本计算表--板换 （5.30-提供） 7.9改-siya.xls/制造件4',
    'A板片组件-铜箔净重'
  )
ON DUPLICATE KEY UPDATE
  net_weight_g = VALUES(net_weight_g),
  source_type = VALUES(source_type),
  source_reference = VALUES(source_reference),
  remark = VALUES(remark),
  updated_at = CURRENT_TIMESTAMP;
