-- =============================================================================
-- 强制 client/connection 用 utf8mb4，否则 docker exec 默认走 latin1 通道
-- 会把中文 utf-8 字节当 latin1 解码再以 utf8mb4 存 → mojibake（2026-04-29 真踩过坑）
SET NAMES utf8mb4;
-- =============================================================================
-- 金标 OA 数据导入：SHF-AA-79 四通阀（料号 1079900000536）
-- -----------------------------------------------------------------------------
-- 用途：把 Excel《产品成本计算表（3.29- 提供）.xls》提取的 OA 样本数据导入系统，
--      方便后续试算对账、前端联调、财务 sign-off。
--
-- 金标目标：含税总成本 152.503 元（不含税，由 GoldenSampleRegressionTest 守门）。
--
-- 数据范围（本脚本只导"OA 主干"三张表；价源/变量/公式数据另由独立脚本导入）：
--   1) oa_form            —— OA 表头 1 行
--   2) oa_form_item       —— OA 产品行项 1 行（整机）
--   3) lp_bom_manage_item —— OA 关联的 BOM 部品明细 29 行
--
-- 命名约定：
--   - oa_no  = 'OA-GOLDEN-001'  统一前缀 OA-GOLDEN-，清库时 WHERE LIKE 一条命令搞定
--   - bom_code = 'BOM-GOLDEN-001'  同上
--
-- 幂等：脚本开头先 DELETE，可安全重复执行。
--
-- 使用方法：
--   mysql -h <host> -u root -p marketing_cost < db/seeds/golden_oa_SHF-AA-79.sql
--
-- ⚠️ 不得在生产环境执行；仅限本地 dev / 测试库。
-- =============================================================================

-- ---------- 1. 清理旧数据（幂等） ----------
DELETE FROM lp_bom_manage_item WHERE oa_no = 'OA-GOLDEN-001';
DELETE FROM oa_form_item       WHERE oa_form_id IN (SELECT id FROM oa_form WHERE oa_no = 'OA-GOLDEN-001');
DELETE FROM oa_form            WHERE oa_no = 'OA-GOLDEN-001';

-- ---------- 2. OA 表头 ----------
-- 含税基价（铜/锌）来自 fixtures/golden/SHF-AA-79/header.json basePrices
-- 其他基价（Al/Steel）金标样本未提供，置 NULL
INSERT INTO oa_form (
  oa_no, form_type, apply_date, customer,
  copper_price, zinc_price, aluminum_price, steel_price,
  other_material, base_shipping, sale_link, remark,
  business_unit_type,
  created_at, updated_at, deleted
) VALUES (
  'OA-GOLDEN-001',                        -- OA 单号（金标前缀）
  '商用-SHF-AA-79',                        -- 表单类型（商用四通阀）
  '2026-04-20',                           -- 申请日期（导入日期）
  'GOLDEN-财务样本',                       -- 客户名（测试占位）
  100000.00,                              -- 铜含税价 Cu=100000
  24662.00,                               -- 锌含税价 Zn=24662
  NULL, NULL, NULL, NULL,                 -- Al/Steel/其他/基础运费：金标未提供
  '财务对账金标',                           -- 销售环节
  'Excel《产品成本计算表（3.29- 提供）.xls》样本，期望不含税总成本 152.503 元',
  'COMMERCIAL',                           -- V21 业务单元隔离（商用四通阀 → COMMERCIAL）
  NOW(), NOW(), 0
);
SET @oa_id = LAST_INSERT_ID();

-- ---------- 3. OA 产品行项（整机 1 行） ----------
-- 从 fixtures/golden/SHF-AA-79/expected_summary.json 汇总字段反填
INSERT INTO oa_form_item (
  oa_form_id, seq, product_name, customer_drawing, material_no, sunl_model, spec,
  shipping_fee, support_qty,
  total_with_ship, total_no_ship,
  material_cost, labor_cost, manufacturing_cost, management_cost,
  business_unit_type,
  valid_date, created_at, updated_at, deleted
) VALUES (
  @oa_id, 1,
  'SHF-AA-79 四通阀',                      -- 产品名
  'SHF-AA-79',                            -- 客户图号
  '1079900000536',                        -- 三花料号
  'SHF-AA-79',                            -- 三花型号
  '四通阀',                                -- 规格
  0.0000,                                 -- 运费：金标未提供
  1.0000,                                 -- 维护量占位
  152.5030,                               -- 含运费总价 = 不含税总成本
  152.5030,                               -- 不含运费总价（金标）
  93.4932,                                -- 材料费（expected_summary.materialTotal）
  4.2210,                                 -- 人工费（labor_rate.directLabor.amount）
  111.3160,                               -- 制造成本（expected_summary.manufactureCost）
  11.1320,                                -- 管理费用（labor_rate.managementAmount）
  'COMMERCIAL',                           -- V21 业务单元隔离（继承自 oa_form）
  '2026-12-31', NOW(), NOW(), 0
);
SET @oa_item_id = LAST_INSERT_ID();

-- ---------- 4. BOM 部品明细（29 行，来自 bom_items.json） ----------
-- 字段映射：
--   drawingNo   → item_code / item_model（系统暂无独立 part_code，用图号兼做）
--   partName    → item_name
--   material    → material
--   formAttr    → shape_attr（采购/自制/联动/原材料联动/家用结算价 → 原值保留）
--   qty         → bom_qty
-- BOM 编码：BOM-GOLDEN-001；根件 = 产品料号 1079900000536
INSERT INTO lp_bom_manage_item (
  oa_no, oa_form_id, oa_form_item_id,
  material_no, product_name, product_spec, product_model, customer_name,
  copper_price_tax, zinc_price_tax, aluminum_price_tax, steel_price_tax,
  bom_code, root_item_code, item_code, item_name, item_spec, item_model,
  shape_attr, bom_qty, material, source, filter_rule,
  business_unit_type,                                      -- V21 业务单元隔离
  created_at, updated_at
) VALUES
-- row 7 防尘帽（采购·固定采购价）
('OA-GOLDEN-001', @oa_id, @oa_item_id, '1079900000536', 'SHF-AA-79 四通阀', '四通阀', 'SHF-AA-79', 'GOLDEN-财务样本',
 100000.00, 24662.00, NULL, NULL,
 'BOM-GOLDEN-001', '1079900000536', 'SHF-000-001010', '防尘帽', 'SHF-000-001010', 'SHF-000-001010',
 '采购', 1, '低密度聚乙烯 LDPE', 'import', 'A', 'COMMERCIAL', NOW(), NOW()),
-- row 8 防尘帽（采购·固定采购价，qty=3）
('OA-GOLDEN-001', @oa_id, @oa_item_id, '1079900000536', 'SHF-AA-79 四通阀', '四通阀', 'SHF-AA-79', 'GOLDEN-财务样本',
 100000.00, 24662.00, NULL, NULL,
 'BOM-GOLDEN-001', '1079900000536', 'SHF-000-001011', '防尘帽', 'SHF-000-001011', 'SHF-000-001011',
 '采购', 3, '低密度聚乙烯 LDPE', 'import', 'A', 'COMMERCIAL', NOW(), NOW()),
-- row 9 滑块部件（采购·固定采购价）
('OA-GOLDEN-001', @oa_id, @oa_item_id, '1079900000536', 'SHF-AA-79 四通阀', '四通阀', 'SHF-AA-79', 'GOLDEN-财务样本',
 100000.00, 24662.00, NULL, NULL,
 'BOM-GOLDEN-001', '1079900000536', 'SHF-H35-002003', '滑块部件', 'SHF-H35-002003', 'SHF-H35-002003',
 '采购', 1, NULL, 'import', 'A', 'COMMERCIAL', NOW(), NOW()),
-- row 10 活塞螺钉（采购·固定采购价，qty=4）
('OA-GOLDEN-001', @oa_id, @oa_item_id, '1079900000536', 'SHF-AA-79 四通阀', '四通阀', 'SHF-AA-79', 'GOLDEN-财务样本',
 100000.00, 24662.00, NULL, NULL,
 'BOM-GOLDEN-001', '1079900000536', 'SHF-H35-010003', '活塞螺钉', 'SHF-H35-010003', 'SHF-H35-010003',
 '采购', 4, 'SWRCH22', 'import', 'A', 'COMMERCIAL', NOW(), NOW()),
-- row 11 端盖（联动价，qty=2；单价 2.94115 × 2 = 5.8823）
('OA-GOLDEN-001', @oa_id, @oa_item_id, '1079900000536', 'SHF-AA-79 四通阀', '四通阀', 'SHF-AA-79', 'GOLDEN-财务样本',
 100000.00, 24662.00, NULL, NULL,
 'BOM-GOLDEN-001', '1079900000536', 'SHF-H35-011002', '端盖', 'SHF-H35-011002', 'SHF-H35-011002',
 '联动', 2, 'H62', 'import', 'A', 'COMMERCIAL', NOW(), NOW()),
-- row 12 活塞部件（采购·固定采购价，qty=2）
('OA-GOLDEN-001', @oa_id, @oa_item_id, '1079900000536', 'SHF-AA-79 四通阀', '四通阀', 'SHF-AA-79', 'GOLDEN-财务样本',
 100000.00, 24662.00, NULL, NULL,
 'BOM-GOLDEN-001', '1079900000536', 'SHF-H35-012002', '活塞部件', 'SHF-H35-012002', 'SHF-H35-012002',
 '采购', 2, NULL, 'import', 'A', 'COMMERCIAL', NOW(), NOW()),
-- row 13 连杆（联动价）
('OA-GOLDEN-001', @oa_id, @oa_item_id, '1079900000536', 'SHF-AA-79 四通阀', '四通阀', 'SHF-AA-79', 'GOLDEN-财务样本',
 100000.00, 24662.00, NULL, NULL,
 'BOM-GOLDEN-001', '1079900000536', 'SHF-H35-020002', '连杆', 'SHF-H35-020002', 'SHF-H35-020002',
 '联动', 1, 'SUS430', 'import', 'A', 'COMMERCIAL', NOW(), NOW()),
-- row 14 弹簧片（联动价）
('OA-GOLDEN-001', @oa_id, @oa_item_id, '1079900000536', 'SHF-AA-79 四通阀', '四通阀', 'SHF-AA-79', 'GOLDEN-财务样本',
 100000.00, 24662.00, NULL, NULL,
 'BOM-GOLDEN-001', '1079900000536', 'SHF-G20-022001', '弹簧片', 'SHF-G20-022001', 'SHF-G20-022001',
 '联动', 1, 'SUS301', 'import', 'A', 'COMMERCIAL', NOW(), NOW()),
-- row 15 S接管（自制 BOM 计算，金标单价 6.658）
('OA-GOLDEN-001', @oa_id, @oa_item_id, '1079900000536', 'SHF-AA-79 四通阀', '四通阀', 'SHF-AA-79', 'GOLDEN-财务样本',
 100000.00, 24662.00, NULL, NULL,
 'BOM-GOLDEN-001', '1079900000536', 'SHF-H35-024008', 'S接管', 'SHF-H35-024008', 'SHF-H35-024008',
 '自制', 1, 'TP2 Y2 Φ23.5×1.3', 'import', 'A', 'COMMERCIAL', NOW(), NOW()),
-- row 16 EC接管（自制 BOM 计算，qty=2）
('OA-GOLDEN-001', @oa_id, @oa_item_id, '1079900000536', 'SHF-AA-79 四通阀', '四通阀', 'SHF-AA-79', 'GOLDEN-财务样本',
 100000.00, 24662.00, NULL, NULL,
 'BOM-GOLDEN-001', '1079900000536', 'SHF-H35-025009', 'EC接管', 'SHF-H35-025009', 'SHF-H35-025009',
 '自制', 2, 'TP2 Y2 Φ23.5×1.3', 'import', 'A', 'COMMERCIAL', NOW(), NOW()),
-- row 17 阀座（联动·固定采购价）
('OA-GOLDEN-001', @oa_id, @oa_item_id, '1079900000536', 'SHF-AA-79 四通阀', '四通阀', 'SHF-AA-79', 'GOLDEN-财务样本',
 100000.00, 24662.00, NULL, NULL,
 'BOM-GOLDEN-001', '1079900000536', 'SHF-H35-026002', '阀座', 'SHF-H35-026002', 'SHF-H35-026002',
 '联动', 1, 'C3604', 'import', 'A', 'COMMERCIAL', NOW(), NOW()),
-- row 18 支架（联动·固定采购价）
('OA-GOLDEN-001', @oa_id, @oa_item_id, '1079900000536', 'SHF-AA-79 四通阀', '四通阀', 'SHF-AA-79', 'GOLDEN-财务样本',
 100000.00, 24662.00, NULL, NULL,
 'BOM-GOLDEN-001', '1079900000536', 'SHF-P20-029001', '支架', 'SHF-P20-029001', 'SHF-P20-029001',
 '联动', 1, 'SUS430', 'import', 'A', 'COMMERCIAL', NOW(), NOW()),
-- row 19 小阀体（联动·固定采购价）
('OA-GOLDEN-001', @oa_id, @oa_item_id, '1079900000536', 'SHF-AA-79 四通阀', '四通阀', 'SHF-AA-79', 'GOLDEN-财务样本',
 100000.00, 24662.00, NULL, NULL,
 'BOM-GOLDEN-001', '1079900000536', 'SHF-000-033601', '小阀体', 'SHF-000-033601', 'SHF-000-033601',
 '联动', 1, 'C3771A', 'import', 'A', 'COMMERCIAL', NOW(), NOW()),
-- row 20 毛细管I（原材料拆解，qty=2）
('OA-GOLDEN-001', @oa_id, @oa_item_id, '1079900000536', 'SHF-AA-79 四通阀', '四通阀', 'SHF-AA-79', 'GOLDEN-财务样本',
 100000.00, 24662.00, NULL, NULL,
 'BOM-GOLDEN-001', '1079900000536', 'SHF-H35-034002', '毛细管I', 'SHF-H35-034002', 'SHF-H35-034002',
 '原材料联动', 2, 'TP2', 'import', 'A', 'COMMERCIAL', NOW(), NOW()),
-- row 21 毛细管II（原材料拆解）
('OA-GOLDEN-001', @oa_id, @oa_item_id, '1079900000536', 'SHF-AA-79 四通阀', '四通阀', 'SHF-AA-79', 'GOLDEN-财务样本',
 100000.00, 24662.00, NULL, NULL,
 'BOM-GOLDEN-001', '1079900000536', 'SHF-H35-035001', '毛细管Ⅱ', 'SHF-H35-035001', 'SHF-H35-035001',
 '原材料联动', 1, 'TP2', 'import', 'A', 'COMMERCIAL', NOW(), NOW()),
-- row 22 小阀座（联动价，商用专用）
('OA-GOLDEN-001', @oa_id, @oa_item_id, '1079900000536', 'SHF-AA-79 四通阀', '四通阀', 'SHF-AA-79', 'GOLDEN-财务样本',
 100000.00, 24662.00, NULL, NULL,
 'BOM-GOLDEN-001', '1079900000536', 'SHF-000-036003', '小阀座', 'SHF-000-036003（商用专用）', 'SHF-000-036003',
 '联动', 1, 'C3604A', 'import', 'A', 'COMMERCIAL', NOW(), NOW()),
-- row 23 毛细管III（原材料拆解）
('OA-GOLDEN-001', @oa_id, @oa_item_id, '1079900000536', 'SHF-AA-79 四通阀', '四通阀', 'SHF-AA-79', 'GOLDEN-财务样本',
 100000.00, 24662.00, NULL, NULL,
 'BOM-GOLDEN-001', '1079900000536', 'SHF-H35-037001', '毛细管Ⅲ', 'SHF-H35-037001', 'SHF-H35-037001',
 '原材料联动', 1, 'TP2', 'import', 'A', 'COMMERCIAL', NOW(), NOW()),
-- row 24 套管（联动价，商用专用）
('OA-GOLDEN-001', @oa_id, @oa_item_id, '1079900000536', 'SHF-AA-79 四通阀', '四通阀', 'SHF-AA-79', 'GOLDEN-财务样本',
 100000.00, 24662.00, NULL, NULL,
 'BOM-GOLDEN-001', '1079900000536', 'SHF-000-038003', '套管', 'SHF-000-038003（商用专用）', 'SHF-000-038003',
 '联动', 1, 'SUS304', 'import', 'A', 'COMMERCIAL', NOW(), NOW()),
-- row 25 过滤网（自制 BOM 计算）
('OA-GOLDEN-001', @oa_id, @oa_item_id, '1079900000536', 'SHF-AA-79 四通阀', '四通阀', 'SHF-AA-79', 'GOLDEN-财务样本',
 100000.00, 24662.00, NULL, NULL,
 'BOM-GOLDEN-001', '1079900000536', 'SHF-000-039004', '过滤网', 'SHF-000-039004', 'SHF-000-039004',
 '自制', 1, '301280056', 'import', 'A', 'COMMERCIAL', NOW(), NOW()),
-- row 26 滑碗（采购·固定采购价）
('OA-GOLDEN-001', @oa_id, @oa_item_id, '1079900000536', 'SHF-AA-79 四通阀', '四通阀', 'SHF-AA-79', 'GOLDEN-财务样本',
 100000.00, 24662.00, NULL, NULL,
 'BOM-GOLDEN-001', '1079900000536', 'SHF-000-042002', '滑碗', 'SHF-000-042002', 'SHF-000-042002',
 '采购', 1, 'PTFE', 'import', 'A', 'COMMERCIAL', NOW(), NOW()),
-- row 27 拖动架（联动价，商用专用）
('OA-GOLDEN-001', @oa_id, @oa_item_id, '1079900000536', 'SHF-AA-79 四通阀', '四通阀', 'SHF-AA-79', 'GOLDEN-财务样本',
 100000.00, 24662.00, NULL, NULL,
 'BOM-GOLDEN-001', '1079900000536', 'SHF-000-046002', '拖动架', 'SHF-000-046002(商用专用)', 'SHF-000-046002',
 '联动', 1, 'H65', 'import', 'A', 'COMMERCIAL', NOW(), NOW()),
-- row 28 簧片（自制 BOM 计算）
('OA-GOLDEN-001', @oa_id, @oa_item_id, '1079900000536', 'SHF-AA-79 四通阀', '四通阀', 'SHF-AA-79', 'GOLDEN-财务样本',
 100000.00, 24662.00, NULL, NULL,
 'BOM-GOLDEN-001', '1079900000536', 'SHF-000-047002', '簧片', 'SHF-000-047002', 'SHF-000-047002',
 '自制', 1, '301240123', 'import', 'A', 'COMMERCIAL', NOW(), NOW()),
-- row 29 D接管（自制 BOM 计算）
('OA-GOLDEN-001', @oa_id, @oa_item_id, '1079900000536', 'SHF-AA-79 四通阀', '四通阀', 'SHF-AA-79', 'GOLDEN-财务样本',
 100000.00, 24662.00, NULL, NULL,
 'BOM-GOLDEN-001', '1079900000536', 'SHF-H35-028002', 'D接管', 'SHF-H35-028002', 'SHF-H35-028002',
 '自制', 1, 'TP2 Y2 Φ20×1.3', 'import', 'A', 'COMMERCIAL', NOW(), NOW()),
-- row 30 阀体（联动价，金标单价 32.6497）
('OA-GOLDEN-001', @oa_id, @oa_item_id, '1079900000536', 'SHF-AA-79 四通阀', '四通阀', 'SHF-AA-79', 'GOLDEN-财务样本',
 100000.00, 24662.00, NULL, NULL,
 'BOM-GOLDEN-001', '1079900000536', 'SHF-H35-027007', '阀体', 'SHF-H35-027007', 'SHF-H35-027007',
 '联动', 1, 'H65', 'import', 'A', 'COMMERCIAL', NOW(), NOW()),
-- row 31 铆钉（采购·固定采购价，material 原值 0.0 视作空）
('OA-GOLDEN-001', @oa_id, @oa_item_id, '1079900000536', 'SHF-AA-79 四通阀', '四通阀', 'SHF-AA-79', 'GOLDEN-财务样本',
 100000.00, 24662.00, NULL, NULL,
 'BOM-GOLDEN-001', '1079900000536', 'SHF-000-088002', '铆钉', 'SHF-000-088002（商用专用）', 'SHF-000-088002',
 '采购', 1, NULL, 'import', 'A', 'COMMERCIAL', NOW(), NOW()),
-- row 32 芯铁（家用结算价）
('OA-GOLDEN-001', @oa_id, @oa_item_id, '1079900000536', 'SHF-AA-79 四通阀', '四通阀', 'SHF-AA-79', 'GOLDEN-财务样本',
 100000.00, 24662.00, NULL, NULL,
 'BOM-GOLDEN-001', '1079900000536', 'SHF-000-044103', '芯铁', 'SHF-000-044103', 'SHF-000-044103',
 '家用结算价', 1, '国产4105I', 'import', 'A', 'COMMERCIAL', NOW(), NOW()),
-- row 33 回复弹簧（采购·固定采购价，商用专用）
('OA-GOLDEN-001', @oa_id, @oa_item_id, '1079900000536', 'SHF-AA-79 四通阀', '四通阀', 'SHF-AA-79', 'GOLDEN-财务样本',
 100000.00, 24662.00, NULL, NULL,
 'BOM-GOLDEN-001', '1079900000536', 'SHF-000-048007', '回复弹簧', 'SHF-000-048007(商用专用)', 'SHF-000-048007',
 '采购', 1, 'SUS304', 'import', 'A', 'COMMERCIAL', NOW(), NOW()),
-- row 34 封头（自制 BOM 计算）
('OA-GOLDEN-001', @oa_id, @oa_item_id, '1079900000536', 'SHF-AA-79 四通阀', '四通阀', 'SHF-AA-79', 'GOLDEN-财务样本',
 100000.00, 24662.00, NULL, NULL,
 'BOM-GOLDEN-001', '1079900000536', 'SHF-000-050203', '封头', 'SHF-000-050203', 'SHF-000-050203',
 '自制', 1, '00Cr13Si2Φ10.2(-0.04/-0.07)', 'import', 'A', 'COMMERCIAL', NOW(), NOW()),
-- row 35 分磁环（家用结算价，商用专用）
('OA-GOLDEN-001', @oa_id, @oa_item_id, '1079900000536', 'SHF-AA-79 四通阀', '四通阀', 'SHF-AA-79', 'GOLDEN-财务样本',
 100000.00, 24662.00, NULL, NULL,
 'BOM-GOLDEN-001', '1079900000536', 'SHF-000-051001', '分磁环', 'SHF-000-051001（商用专用）', 'SHF-000-051001',
 '家用结算价', 1, 'T2', 'import', 'A', 'COMMERCIAL', NOW(), NOW());

-- ---------- 5. 导入结果校验（人工执行） ----------
-- 以下查询脚本完成后手工跑，确认数据齐全：
--   SELECT * FROM oa_form WHERE oa_no = 'OA-GOLDEN-001';
--     → 1 行，customer='GOLDEN-财务样本'，copper_price=100000.00
--
--   SELECT id, product_name, material_no, total_no_ship FROM oa_form_item
--     WHERE oa_form_id = (SELECT id FROM oa_form WHERE oa_no = 'OA-GOLDEN-001');
--     → 1 行，material_no='1079900000536'，total_no_ship=152.5030
--
--   SELECT COUNT(*) FROM lp_bom_manage_item WHERE oa_no = 'OA-GOLDEN-001';
--     → 29（金标 29 行部品）
--
--   SELECT shape_attr, COUNT(*) FROM lp_bom_manage_item
--     WHERE oa_no = 'OA-GOLDEN-001' GROUP BY shape_attr;
--     → 采购 8 / 联动 10 / 自制 6 / 原材料联动 3 / 家用结算价 2 / 合计 29
--     （自制 6 件走 BOM 计算桶：S接管 / EC接管 / 过滤网 / 簧片 / D接管 / 封头）
--     （原材料联动 3 件走 RAW_BREAKDOWN 桶：毛细管 I / II / III）
--
-- ---------- 6. 一键清理 ----------
--   DELETE FROM lp_bom_manage_item WHERE oa_no = 'OA-GOLDEN-001';
--   DELETE FROM oa_form_item WHERE oa_form_id IN (SELECT id FROM oa_form WHERE oa_no = 'OA-GOLDEN-001');
--   DELETE FROM oa_form WHERE oa_no = 'OA-GOLDEN-001';
