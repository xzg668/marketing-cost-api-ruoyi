-- ===========================================================
-- 测试数据包 · 02_bom_manage_item.sql
-- 29 行 BOM，覆盖 5 种价格类型
-- ===========================================================
-- 字段说明：
--   material_no        = 父产品料号 = 1079900000536 (SHF-AA-79)
--   item_code          = 零件料号（9 位数字，和价格类型表 material_code 对齐）
--   item_spec          = 零件规格（SHF-xxx，Excel 产品明细1 原文）
--   shape_attr         = 形态属性（'部品联动' 走联动价；其他走对应价格表）
--   copper_price_tax 等 = 从 oa_form 同步（BOM 缓存一份，和 oa_form 保持一致）
--   business_unit_type = COMMERCIAL（V21 之后按 BU 隔离）
--   oa_form_id / oa_form_item_id = 关联到 01_oa_form.sql 造的 OA 单行
--
-- 依赖：先执行 00_fix_schema.sql 和 01_oa_form.sql
-- ===========================================================

-- 先把关联 id 拿到 SESSION 变量
SET @oa_form_id = (SELECT id FROM oa_form WHERE oa_no = 'OA-GOLDEN-001' LIMIT 1);
SET @oa_form_item_id = (
  SELECT i.id FROM oa_form_item i
    JOIN oa_form o ON i.oa_form_id = o.id
  WHERE o.oa_no = 'OA-GOLDEN-001' AND i.material_no = '1079900000536'
  LIMIT 1);

-- 防呆断言：若变量为 NULL 则抛错中止，不让 INSERT 产出带 NULL 外键的坏数据。
-- MySQL 没有原生 RAISE，用"故意触发数据类型错误"的方式让脚本停下。
-- 如果 @oa_form_id 或 @oa_form_item_id 为 NULL，说明 01_oa_form.sql 没跑成功（或顺序错），
-- 这时 SIGNAL 抛错会让 mysql CLI 退出，提醒你先跑 01。
DROP PROCEDURE IF EXISTS _assert_oa_ids_not_null;
DELIMITER //
CREATE PROCEDURE _assert_oa_ids_not_null()
BEGIN
  IF @oa_form_id IS NULL OR @oa_form_item_id IS NULL THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '前置数据缺失：@oa_form_id 或 @oa_form_item_id 为 NULL。请先执行 01_oa_form.sql 建好 oa_form + oa_form_item，再跑本脚本。';
  END IF;
END //
DELIMITER ;
CALL _assert_oa_ids_not_null();
DROP PROCEDURE _assert_oa_ids_not_null;

-- 辅助：打印两个变量值，执行过程中能看到（正常非空）
SELECT @oa_form_id AS oa_form_id, @oa_form_item_id AS oa_form_item_id;

INSERT INTO lp_bom_manage_item (
  oa_no, oa_form_id, oa_form_item_id, material_no, product_name, product_spec, product_model, customer_name,
  copper_price_tax, zinc_price_tax, aluminum_price_tax, steel_price_tax,
  bom_code, root_item_code, item_code, item_name, item_spec, item_model, shape_attr, bom_qty,
  material, source, filter_rule, business_unit_type,
  created_at, updated_at
) VALUES
  ('OA-GOLDEN-001', @oa_form_id, @oa_form_item_id, '1079900000536', '四通换向阀', 'SHF-AA-79', 'SHF-AA-79', 'GOLDEN-财务样本',
   90000, 21684, 23386, 17200,
   'BOM-GOLDEN-SHF-AA-79', '1079900000536', '201802072', '防尘帽', 'SHF-000-001010', 'SHF-000-001010', '采购件', 1,
   '', 'TEST', 'A', 'COMMERCIAL', NOW(), NOW()),
  ('OA-GOLDEN-001', @oa_form_id, @oa_form_item_id, '1079900000536', '四通换向阀', 'SHF-AA-79', 'SHF-AA-79', 'GOLDEN-财务样本',
   90000, 21684, 23386, 17200,
   'BOM-GOLDEN-SHF-AA-79', '1079900000536', '201802073', '防尘帽', 'SHF-000-001011', 'SHF-000-001011', '采购件', 3,
   '', 'TEST', 'A', 'COMMERCIAL', NOW(), NOW()),
  ('OA-GOLDEN-001', @oa_form_id, @oa_form_item_id, '1079900000536', '四通换向阀', 'SHF-AA-79', 'SHF-AA-79', 'GOLDEN-财务样本',
   90000, 21684, 23386, 17200,
   'BOM-GOLDEN-SHF-AA-79', '1079900000536', '203250344', '滑块部件', 'SHF-H35-002003', 'SHF-H35-002003', '采购件', 1,
   '', 'TEST', 'A', 'COMMERCIAL', NOW(), NOW()),
  ('OA-GOLDEN-001', @oa_form_id, @oa_form_item_id, '1079900000536', '四通换向阀', 'SHF-AA-79', 'SHF-AA-79', 'GOLDEN-财务样本',
   90000, 21684, 23386, 17200,
   'BOM-GOLDEN-SHF-AA-79', '1079900000536', '203250385', '活塞螺钉', 'SHF-H35-010003', 'SHF-H35-010003', '采购件', 4,
   '', 'TEST', 'A', 'COMMERCIAL', NOW(), NOW()),
  ('OA-GOLDEN-001', @oa_form_id, @oa_form_item_id, '1079900000536', '四通换向阀', 'SHF-AA-79', 'SHF-AA-79', 'GOLDEN-财务样本',
   90000, 21684, 23386, 17200,
   'BOM-GOLDEN-SHF-AA-79', '1079900000536', '203259319', '端盖', 'SHF-H35-011002', 'SHF-H35-011002', '部品联动', 2,
   '', 'TEST', 'A', 'COMMERCIAL', NOW(), NOW()),
  ('OA-GOLDEN-001', @oa_form_id, @oa_form_item_id, '1079900000536', '四通换向阀', 'SHF-AA-79', 'SHF-AA-79', 'GOLDEN-财务样本',
   90000, 21684, 23386, 17200,
   'BOM-GOLDEN-SHF-AA-79', '1079900000536', '203250373', '活塞部件', 'SHF-H35-012002', 'SHF-H35-012002', '采购件', 2,
   '', 'TEST', 'A', 'COMMERCIAL', NOW(), NOW()),
  ('OA-GOLDEN-001', @oa_form_id, @oa_form_item_id, '1079900000536', '四通换向阀', 'SHF-AA-79', 'SHF-AA-79', 'GOLDEN-财务样本',
   90000, 21684, 23386, 17200,
   'BOM-GOLDEN-SHF-AA-79', '1079900000536', '203250307', '连杆', 'SHF-H35-020002', 'SHF-H35-020002', '部品联动', 1,
   '', 'TEST', 'A', 'COMMERCIAL', NOW(), NOW()),
  ('OA-GOLDEN-001', @oa_form_id, @oa_form_item_id, '1079900000536', '四通换向阀', 'SHF-AA-79', 'SHF-AA-79', 'GOLDEN-财务样本',
   90000, 21684, 23386, 17200,
   'BOM-GOLDEN-SHF-AA-79', '1079900000536', '203259729', '弹簧片', 'SHF-G20-022001', 'SHF-G20-022001', '部品联动', 1,
   '', 'TEST', 'A', 'COMMERCIAL', NOW(), NOW()),
  ('OA-GOLDEN-001', @oa_form_id, @oa_form_item_id, '1079900000536', '四通换向阀', 'SHF-AA-79', 'SHF-AA-79', 'GOLDEN-财务样本',
   90000, 21684, 23386, 17200,
   'BOM-GOLDEN-SHF-AA-79', '1079900000536', '203250749', 'S接管', 'SHF-H35-024008', 'SHF-H35-024008', '自制件', 1,
   '', 'TEST', 'A', 'COMMERCIAL', NOW(), NOW()),
  ('OA-GOLDEN-001', @oa_form_id, @oa_form_item_id, '1079900000536', '四通换向阀', 'SHF-AA-79', 'SHF-AA-79', 'GOLDEN-财务样本',
   90000, 21684, 23386, 17200,
   'BOM-GOLDEN-SHF-AA-79', '1079900000536', '203250724', 'EC接管', 'SHF-H35-025009', 'SHF-H35-025009', '自制件', 2,
   '', 'TEST', 'A', 'COMMERCIAL', NOW(), NOW()),
  ('OA-GOLDEN-001', @oa_form_id, @oa_form_item_id, '1079900000536', '四通换向阀', 'SHF-AA-79', 'SHF-AA-79', 'GOLDEN-财务样本',
   90000, 21684, 23386, 17200,
   'BOM-GOLDEN-SHF-AA-79', '1079900000536', '203250445', '阀座', 'SHF-H35-026002', 'SHF-H35-026002', '部品联动', 1,
   '', 'TEST', 'A', 'COMMERCIAL', NOW(), NOW()),
  ('OA-GOLDEN-001', @oa_form_id, @oa_form_item_id, '1079900000536', '四通换向阀', 'SHF-AA-79', 'SHF-AA-79', 'GOLDEN-财务样本',
   90000, 21684, 23386, 17200,
   'BOM-GOLDEN-SHF-AA-79', '1079900000536', '203259786', '支架', 'SHF-P20-029001', 'SHF-P20-029001', '部品联动', 1,
   '', 'TEST', 'A', 'COMMERCIAL', NOW(), NOW()),
  ('OA-GOLDEN-001', @oa_form_id, @oa_form_item_id, '1079900000536', '四通换向阀', 'SHF-AA-79', 'SHF-AA-79', 'GOLDEN-财务样本',
   90000, 21684, 23386, 17200,
   'BOM-GOLDEN-SHF-AA-79', '1079900000536', '203259838', '小阀体', 'SHF-000-033601', 'SHF-000-033601', '部品联动', 1,
   '', 'TEST', 'A', 'COMMERCIAL', NOW(), NOW()),
  ('OA-GOLDEN-001', @oa_form_id, @oa_form_item_id, '1079900000536', '四通换向阀', 'SHF-AA-79', 'SHF-AA-79', 'GOLDEN-财务样本',
   90000, 21684, 23386, 17200,
   'BOM-GOLDEN-SHF-AA-79', '1079900000536', '203250606', '毛细管Ⅰ', 'SHF-H35-034002', 'SHF-H35-034002', '部品联动', 2,
   '', 'TEST', 'A', 'COMMERCIAL', NOW(), NOW()),
  ('OA-GOLDEN-001', @oa_form_id, @oa_form_item_id, '1079900000536', '四通换向阀', 'SHF-AA-79', 'SHF-AA-79', 'GOLDEN-财务样本',
   90000, 21684, 23386, 17200,
   'BOM-GOLDEN-SHF-AA-79', '1079900000536', '203250625', '毛细管Ⅱ', 'SHF-H35-035001', 'SHF-H35-035001', '部品联动', 1,
   '', 'TEST', 'A', 'COMMERCIAL', NOW(), NOW()),
  ('OA-GOLDEN-001', @oa_form_id, @oa_form_item_id, '1079900000536', '四通换向阀', 'SHF-AA-79', 'SHF-AA-79', 'GOLDEN-财务样本',
   90000, 21684, 23386, 17200,
   'BOM-GOLDEN-SHF-AA-79', '1079900000536', '203240251', '小阀座', 'SHF-000-036003（商用专用）', 'SHF-000-036003（商用专用）', '部品联动', 1,
   '', 'TEST', 'A', 'COMMERCIAL', NOW(), NOW()),
  ('OA-GOLDEN-001', @oa_form_id, @oa_form_item_id, '1079900000536', '四通换向阀', 'SHF-AA-79', 'SHF-AA-79', 'GOLDEN-财务样本',
   90000, 21684, 23386, 17200,
   'BOM-GOLDEN-SHF-AA-79', '1079900000536', '203250633', '毛细管Ⅲ', 'SHF-H35-037001', 'SHF-H35-037001', '部品联动', 1,
   '', 'TEST', 'A', 'COMMERCIAL', NOW(), NOW()),
  ('OA-GOLDEN-001', @oa_form_id, @oa_form_item_id, '1079900000536', '四通换向阀', 'SHF-AA-79', 'SHF-AA-79', 'GOLDEN-财务样本',
   90000, 21684, 23386, 17200,
   'BOM-GOLDEN-SHF-AA-79', '1079900000536', '203240231', '套管', 'SHF-000-038003（商用专用）', 'SHF-000-038003（商用专用）', '部品联动', 1,
   '', 'TEST', 'A', 'COMMERCIAL', NOW(), NOW()),
  ('OA-GOLDEN-001', @oa_form_id, @oa_form_item_id, '1079900000536', '四通换向阀', 'SHF-AA-79', 'SHF-AA-79', 'GOLDEN-财务样本',
   90000, 21684, 23386, 17200,
   'BOM-GOLDEN-SHF-AA-79', '1079900000536', '301280056', '过滤网', 'SHF-000-039004', 'SHF-000-039004', '自制件', 1,
   '', 'TEST', 'A', 'COMMERCIAL', NOW(), NOW()),
  ('OA-GOLDEN-001', @oa_form_id, @oa_form_item_id, '1079900000536', '四通换向阀', 'SHF-AA-79', 'SHF-AA-79', 'GOLDEN-财务样本',
   90000, 21684, 23386, 17200,
   'BOM-GOLDEN-SHF-AA-79', '1079900000536', '201800082', '滑碗', 'SHF-000-042002', 'SHF-000-042002', '采购件', 1,
   '', 'TEST', 'A', 'COMMERCIAL', NOW(), NOW()),
  ('OA-GOLDEN-001', @oa_form_id, @oa_form_item_id, '1079900000536', '四通换向阀', 'SHF-AA-79', 'SHF-AA-79', 'GOLDEN-财务样本',
   90000, 21684, 23386, 17200,
   'BOM-GOLDEN-SHF-AA-79', '1079900000536', '203240608', '拖动架', 'SHF-000-046002(商用专用)', 'SHF-000-046002(商用专用)', '部品联动', 1,
   '', 'TEST', 'A', 'COMMERCIAL', NOW(), NOW()),
  ('OA-GOLDEN-001', @oa_form_id, @oa_form_item_id, '1079900000536', '四通换向阀', 'SHF-AA-79', 'SHF-AA-79', 'GOLDEN-财务样本',
   90000, 21684, 23386, 17200,
   'BOM-GOLDEN-SHF-AA-79', '1079900000536', '201800231', '簧片', 'SHF-000-047002', 'SHF-000-047002', '自制件', 1,
   '', 'TEST', 'A', 'COMMERCIAL', NOW(), NOW()),
  ('OA-GOLDEN-001', @oa_form_id, @oa_form_item_id, '1079900000536', '四通换向阀', 'SHF-AA-79', 'SHF-AA-79', 'GOLDEN-财务样本',
   90000, 21684, 23386, 17200,
   'BOM-GOLDEN-SHF-AA-79', '1079900000536', '203250582', 'D接管', 'SHF-H35-028002', 'SHF-H35-028002', '自制件', 1,
   '', 'TEST', 'A', 'COMMERCIAL', NOW(), NOW()),
  ('OA-GOLDEN-001', @oa_form_id, @oa_form_item_id, '1079900000536', '四通换向阀', 'SHF-AA-79', 'SHF-AA-79', 'GOLDEN-财务样本',
   90000, 21684, 23386, 17200,
   'BOM-GOLDEN-SHF-AA-79', '1079900000536', '203250427', '阀体', 'SHF-H35-027007', 'SHF-H35-027007', '部品联动', 1,
   '', 'TEST', 'A', 'COMMERCIAL', NOW(), NOW()),
  ('OA-GOLDEN-001', @oa_form_id, @oa_form_item_id, '1079900000536', '四通换向阀', 'SHF-AA-79', 'SHF-AA-79', 'GOLDEN-财务样本',
   90000, 21684, 23386, 17200,
   'BOM-GOLDEN-SHF-AA-79', '1079900000536', '203240246', '扁平头铆钉', 'SHF-000-088002（商用专用）', 'SHF-000-088002（商用专用）', '采购件', 1,
   '', 'TEST', 'A', 'COMMERCIAL', NOW(), NOW()),
  ('OA-GOLDEN-001', @oa_form_id, @oa_form_item_id, '1079900000536', '四通换向阀', 'SHF-AA-79', 'SHF-AA-79', 'GOLDEN-财务样本',
   90000, 21684, 23386, 17200,
   'BOM-GOLDEN-SHF-AA-79', '1079900000536', '203259840', '芯铁', 'SHF-000-044103', 'SHF-000-044103', '采购件', 1,
   '', 'TEST', 'A', 'COMMERCIAL', NOW(), NOW()),
  ('OA-GOLDEN-001', @oa_form_id, @oa_form_item_id, '1079900000536', '四通换向阀', 'SHF-AA-79', 'SHF-AA-79', 'GOLDEN-财务样本',
   90000, 21684, 23386, 17200,
   'BOM-GOLDEN-SHF-AA-79', '1079900000536', '203240747', '回复弹簧', 'SHF-000-048007(商用专用)', 'SHF-000-048007(商用专用)', '采购件', 1,
   '', 'TEST', 'A', 'COMMERCIAL', NOW(), NOW()),
  ('OA-GOLDEN-001', @oa_form_id, @oa_form_item_id, '1079900000536', '四通换向阀', 'SHF-AA-79', 'SHF-AA-79', 'GOLDEN-财务样本',
   90000, 21684, 23386, 17200,
   'BOM-GOLDEN-SHF-AA-79', '1079900000536', '203240252', '封头', 'SHF-000-050203', 'SHF-000-050203', '自制件', 1,
   '', 'TEST', 'A', 'COMMERCIAL', NOW(), NOW()),
  ('OA-GOLDEN-001', @oa_form_id, @oa_form_item_id, '1079900000536', '四通换向阀', 'SHF-AA-79', 'SHF-AA-79', 'GOLDEN-财务样本',
   90000, 21684, 23386, 17200,
   'BOM-GOLDEN-SHF-AA-79', '1079900000536', '203240247', '分磁环', 'SHF-000-051001（商用专用）', 'SHF-000-051001（商用专用）', '采购件', 1,
   '', 'TEST', 'A', 'COMMERCIAL', NOW(), NOW());

-- 验证
SELECT oa_no, oa_form_id, oa_form_item_id, item_code, item_name, item_spec, shape_attr, bom_qty, business_unit_type
FROM lp_bom_manage_item WHERE oa_no = 'OA-GOLDEN-001' ORDER BY id;
