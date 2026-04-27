-- ===========================================================
-- 测试数据包 · 01_oa_form.sql
-- 产品：1079900000536（SHF-AA-79）· OA 单：OA-GOLDEN-001
-- 目的：端到端联动价测试 / 月度调价对账
-- ===========================================================
--
-- 本文件造两张表的数据：
--   oa_form       —— OA 单单头（1 行），含 Cu/Zn/Al/Sn 金属价快照
--   oa_form_item  —— OA 单行（1 行），描述 SHF-AA-79 这个产品
--
-- 说明：OA-GOLDEN-001 已存在于库里（之前 seed），此文件作为"重置到测试基线"
-- 幂等：多次执行结果一致。
--
-- 金属价说明（对应 Excel 金标 / 影响因素 10 2026-02）：
--   copper_price   = 90000 元/吨（Cu 90 元/kg）
--   zinc_price     = 21684 元/吨（Zn 21.684 元/kg）
--   aluminum_price = 23386 元/吨（Al 23.386 元/kg）
--   steel_price    = 17200 元/吨（SUS304/2Bδ0.7 17.2 元/kg）
-- ===========================================================

-- ---- 清子表（让重跑干净）----
DELETE FROM lp_bom_manage_item WHERE oa_no = 'OA-GOLDEN-001';
DELETE FROM lp_price_linked_calc_item WHERE oa_no = 'OA-GOLDEN-001';

-- 清 oa_form_item 要通过关联 oa_form.id 来删（因为它没有 oa_no 列）
DELETE FROM oa_form_item
WHERE oa_form_id IN (SELECT id FROM oa_form WHERE oa_no = 'OA-GOLDEN-001');

-- ---- OA 单单头（oa_form）----
-- 用 INSERT ... ON DUPLICATE KEY UPDATE 保证幂等
INSERT INTO oa_form (
  oa_no, form_type, apply_date, customer,
  copper_price, zinc_price, aluminum_price, steel_price, other_material,
  base_shipping, calc_status, sale_link, remark, business_unit_type,
  created_at, updated_at
) VALUES (
  'OA-GOLDEN-001', '商用-SHF-AA-79', '2026-04-20', 'GOLDEN-财务样本',
  90000, 21684, 23386, 17200, NULL,
  NULL, '未核算', NULL, '金标测试单（SHF-AA-79）', 'COMMERCIAL',
  NOW(), NOW()
) ON DUPLICATE KEY UPDATE
  form_type = VALUES(form_type),
  customer = VALUES(customer),
  copper_price = VALUES(copper_price),
  zinc_price = VALUES(zinc_price),
  aluminum_price = VALUES(aluminum_price),
  steel_price = VALUES(steel_price),
  business_unit_type = VALUES(business_unit_type),
  remark = VALUES(remark),
  updated_at = NOW();

-- ---- OA 单行（oa_form_item）----
-- 这个 OA 单只有 1 个产品型号：SHF-AA-79
-- material_no = 产品料号 1079900000536（和 BOM 里的 material_no 对齐）
-- business_unit_type = COMMERCIAL（V21 之后冗余一份在行表上加速过滤）
-- 成本类字段（material_cost / labor_cost 等）先留空，等成本试算后由系统回填
INSERT INTO oa_form_item (
  oa_form_id, business_unit_type, seq,
  product_name, customer_drawing, material_no, sunl_model, spec,
  shipping_fee, support_qty, total_with_ship, total_no_ship,
  material_cost, labor_cost, manufacturing_cost, management_cost,
  valid_date, created_at, updated_at, deleted
) VALUES (
  (SELECT id FROM oa_form WHERE oa_no = 'OA-GOLDEN-001'),
  'COMMERCIAL', 1,
  '四通换向阀', 'SHF-AA-79', '1079900000536', 'SHF-AA-79', 'SHF-AA-79',
  NULL, 1, NULL, NULL,
  NULL, NULL, NULL, NULL,
  '2026-12-31', NOW(), NOW(), 0
);

-- ---- 验证 ----
SELECT oa_no, form_type, customer, copper_price, zinc_price, aluminum_price, steel_price, business_unit_type
FROM oa_form WHERE oa_no = 'OA-GOLDEN-001';

SELECT i.seq, i.business_unit_type, i.product_name, i.customer_drawing, i.material_no, i.sunl_model, i.spec, i.valid_date
FROM oa_form_item i
  JOIN oa_form o ON i.oa_form_id = o.id
WHERE o.oa_no = 'OA-GOLDEN-001';
