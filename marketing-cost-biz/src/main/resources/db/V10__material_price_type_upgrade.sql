-- =====================================================================
-- V10：lp_material_price_type 升级 —— 价格路由表扩展
--   背景：原表已有 material_shape（采购件/制造件）+ price_type（固定价/联动价），
--         本次升级为接入 5 类价格来源（固定/联动/结算/区间/BOM计算/原材料拆解）
--         + 优先级回退 + 生效期窗口 + 数据来源标识。
--   兼容：保留现有列名与约束；只做 ADD COLUMN / ADD INDEX，不破坏现有查询。
--   验收：金标产品 1079900000536 (SHF-AA-79) 的 35 行部品按 (material_shape, price_type)
--         能命中 5 类价源，最终 totalCostExclTax = 152.503 ± 0.01。
-- =====================================================================

-- 1) 优先级：同一物料登记多条价格类型时的取价顺序（小者先取，查不到才回退）
ALTER TABLE `lp_material_price_type`
  ADD COLUMN `priority` TINYINT NOT NULL DEFAULT 1
    COMMENT '取价优先级（1=最高），同物料多条时小者优先，查不到则降级';

-- 2) 生效期窗口：让历史报价不被新维护污染
ALTER TABLE `lp_material_price_type`
  ADD COLUMN `effective_from` DATE NULL DEFAULT NULL
    COMMENT '生效起始日期（含），NULL 表示不限早',
  ADD COLUMN `effective_to`   DATE NULL DEFAULT NULL
    COMMENT '生效结束日期（含），NULL 表示长期有效';

-- 3) 数据来源标识：未来对接 SRM/OA/U9/CMS 多源时的来源追溯
ALTER TABLE `lp_material_price_type`
  ADD COLUMN `source_system` VARCHAR(16) NOT NULL DEFAULT 'manual'
    COMMENT '数据来源：srm/oa/u9/cms/manual';

-- 4) 索引：覆盖"按物料 + 形态 + 优先级 + 生效期"的高频查询路径
ALTER TABLE `lp_material_price_type`
  ADD INDEX `idx_material_shape_priority`
    (`material_code`, `material_shape`, `priority`),
  ADD INDEX `idx_effective` (`effective_from`, `effective_to`);

-- 5) 字段取值规范说明（不上 CHECK，靠应用层 enum 强约束以兼容历史数据）：
--    material_shape ∈ { '采购件', '制造件', '委外加工件' }
--    price_type 在不同形态下的合法取值（由 MaterialPriceTypeEnum 校验）：
--      采购件     → { 固定价, 联动价, 区间价, 结算价 }
--      制造件     → { BOM计算, 联动价, 固定价 }   -- 制造件预留外协联动 / 外协固定
--      委外加工件 → { 固定价, 结算价 }
--    新增 price_type 枚举：BOM计算 / 区间价 / 结算价 / 原材料拆解
ALTER TABLE `lp_material_price_type`
  MODIFY COLUMN `material_shape` VARCHAR(32) COLLATE utf8mb3_bin DEFAULT NULL
    COMMENT '形态属性：采购件/制造件/委外加工件',
  MODIFY COLUMN `price_type`     VARCHAR(32) COLLATE utf8mb3_bin NOT NULL
    COMMENT '价格类型：固定价/联动价/区间价/结算价/BOM计算/原材料拆解';

-- 6) source 字段语义升级：原 source 标记导入批次（保留），source_system 标记数据系统来源（新增）
--    两者并存，import 工具同时填充。
