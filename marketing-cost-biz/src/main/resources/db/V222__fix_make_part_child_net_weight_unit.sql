-- V186 的 A板片组件两条净重把 U9 副产品千克用量错误地除以 1000 后从克制毛重中扣减。
-- 正确口径：副产品用量(kg) * 1000 = 废料重量(g)，净重(g) = 毛重(g) - 废料重量(g)。

UPDATE lp_make_part_child_net_weight
SET net_weight_g = 33.60000000,
    source_type = 'U9_BYPRODUCT_RECALC',
    source_reference = 'lp_u9_bom_byproduct_master:301990044@F001',
    remark = '毛重37.9g-副产品0.0043kg*1000=净重33.6g',
    updated_at = CURRENT_TIMESTAMP
WHERE material_organization_code = 'PLATE'
  AND parent_material_no = '1053000301687'
  AND child_material_no = '301240299'
  AND bom_version = 'F001'
  AND period_month = '2026-07';

UPDATE lp_make_part_child_net_weight
SET net_weight_g = 5.00000000,
    source_type = 'U9_BYPRODUCT_RECALC',
    source_reference = 'lp_u9_bom_byproduct_master:301990315@F001',
    remark = '毛重5.6g-副产品0.0006kg*1000=净重5.0g',
    updated_at = CURRENT_TIMESTAMP
WHERE material_organization_code = 'PLATE'
  AND parent_material_no = '1053000301687'
  AND child_material_no = '301070047'
  AND bom_version = 'F001'
  AND period_month = '2026-07';
