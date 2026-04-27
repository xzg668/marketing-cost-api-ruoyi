-- =================================================================
-- V31: lp_price_variable 统一解析模型（Plan B）
--
-- 背景：
--   变量解析现有三代模型并存，FactorVariableRegistryImpl 各分支要读不同列：
--     V0  -> source_table / source_field（legacy，实际无人读）
--     V13 -> default_value / formula_expr（CONST / FORMULA_REF 专用）
--     V24 -> context_binding_json         （PART_CONTEXT 专用）
--   且 FINANCE_FACTOR 分支把 variable_name 当 short_name 查 lp_finance_base_price，
--   不过滤 price_source / business_unit_type，与 legacy 计算路径的
--   (factor_code + price_source + BU) 四键查询完全对不上，导致
--   /items/{id}/trace 对 FINANCE_FACTOR 变量始终返回 0。
--
-- 方案（Plan B）：一次性收敛为一代模型。
--   resolver_kind   VARCHAR(16)  —— FINANCE / ENTITY / DERIVED / FORMULA / CONST
--   resolver_params JSON         —— 各 kind 自描述参数（见下表）
--
--   factor_type 保留不删（前端 /variables/catalog 三大分组仍按它分），
--   与 resolver_kind 正交：前者是 UI 分组标签，后者是后端解析器分发键。
--
--   各 kind 的 params 契约：
--     FINANCE : {"factorCode":"Cu","priceSource":"平均价","buScoped":true}
--                或 {"shortName":"美国柜装黄铜","priceSource":"平均价","buScoped":true}
--                （factorCode 优先；两者互斥）
--     ENTITY  : {"entity":"linkedItem","field":"blankWeight","unitScale":0.001}
--                （entity = Java bean 名，field = camelCase getter 后缀）
--     DERIVED : {"strategy":"MAIN_MATERIAL_FINANCE"}
--                {"strategy":"SCRAP_REF"}
--                {"strategy":"FORMULA_REF","formulaRef":"[Cu]*0.59+[Zn]*0.41"}
--                {"strategy":"FINANCE_FACTOR","factorCode":"美国柜装黄铜"}
--     FORMULA : {"expr":"[Cu]/(1+[vat_rate])"}
--     CONST   : {"value":"0.13"}
--
-- 不做（留作 V32 及后续任务）：
--   1) DROP 一/二代列 —— 本次只新增 + 回填，Java 侧停止读取即可；
--      保留列给回滚空间。
--   2) FINANCE_FACTOR 的 Al / Sn / Cn 回填 —— finance 表无对应数据
--      （这三个只走 OA 锁价路径），保持 resolver_kind NULL，
--      registry 命中 NULL → WARN + 返回 empty，符合"数据未配置即无法解析"预期。
--   3) 若此前手工执行过旧版 V31__finance_factor_resolver_columns.sql，
--      其留下的 4 列视为死列，由 V32 统一清理，不影响本次迁移。
-- =================================================================

-- ---------------------------------------------------------------
-- 1) 加 2 列（INFORMATION_SCHEMA 幂等守护，重跑不报 Duplicate column）
-- ---------------------------------------------------------------
SET @has_resolver_kind := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME   = 'lp_price_variable'
     AND COLUMN_NAME  = 'resolver_kind');
SET @sql := IF(@has_resolver_kind = 0,
  'ALTER TABLE `lp_price_variable`
     ADD COLUMN `resolver_kind`   VARCHAR(16) NULL
       COMMENT ''FINANCE/ENTITY/DERIVED/FORMULA/CONST —— 解析器分发键''
       AFTER `context_binding_json`,
     ADD COLUMN `resolver_params` JSON        NULL
       COMMENT ''对应 resolver_kind 的自描述参数（factorCode/priceSource/entity/field/strategy/expr/value 等）''
       AFTER `resolver_kind`,
     ADD KEY `idx_resolver_kind` (`resolver_kind`)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------
-- 2) 回填 FINANCE（已知 3 条：Cu / Zn / us_brass_price）
--    Al / Sn / Cn 留 NULL —— 详见文件头说明
-- ---------------------------------------------------------------
-- Cu：按 factor_code='Cu'，取"平均价"价源，商用隔离
UPDATE `lp_price_variable` SET
    resolver_kind   = 'FINANCE',
    resolver_params = JSON_OBJECT(
        'factorCode',  'Cu',
        'priceSource', '平均价',
        'buScoped',    TRUE)
  WHERE variable_code = 'Cu' AND factor_type = 'FINANCE_FACTOR';

-- Zn：同上
UPDATE `lp_price_variable` SET
    resolver_kind   = 'FINANCE',
    resolver_params = JSON_OBJECT(
        'factorCode',  'Zn',
        'priceSource', '平均价',
        'buScoped',    TRUE)
  WHERE variable_code = 'Zn' AND factor_type = 'FINANCE_FACTOR';

-- us_brass_price：finance 表只有 short_name，无 factor_code
UPDATE `lp_price_variable` SET
    resolver_kind   = 'FINANCE',
    resolver_params = JSON_OBJECT(
        'shortName',   '美国柜装黄铜',
        'priceSource', '平均价',
        'buScoped',    TRUE)
  WHERE variable_code = 'us_brass_price' AND factor_type = 'FINANCE_FACTOR';

-- ---------------------------------------------------------------
-- 3) 回填 ENTITY（从 context_binding_json 搬运；字段契约已对齐）
--    现有 JSON 形如 {"source":"ENTITY","entity":"linkedItem","field":"blankWeight","unitScale":0.001}
--    直接复用即可，registry 只读 entity/field/unitScale，冗余的 source 键无副作用
-- ---------------------------------------------------------------
UPDATE `lp_price_variable` SET
    resolver_kind   = 'ENTITY',
    resolver_params = context_binding_json
  WHERE factor_type = 'PART_CONTEXT'
    AND JSON_EXTRACT(context_binding_json, '$.source') = 'ENTITY';

-- ---------------------------------------------------------------
-- 4) 回填 DERIVED（4 种 strategy 原样搬运）
-- ---------------------------------------------------------------
UPDATE `lp_price_variable` SET
    resolver_kind   = 'DERIVED',
    resolver_params = context_binding_json
  WHERE factor_type = 'PART_CONTEXT'
    AND JSON_EXTRACT(context_binding_json, '$.source') = 'DERIVED';

-- ---------------------------------------------------------------
-- 5) 回填 FORMULA（从 formula_expr 搬运）
-- ---------------------------------------------------------------
UPDATE `lp_price_variable` SET
    resolver_kind   = 'FORMULA',
    resolver_params = JSON_OBJECT('expr', formula_expr)
  WHERE factor_type  = 'FORMULA_REF'
    AND formula_expr IS NOT NULL
    AND formula_expr <> '';

-- ---------------------------------------------------------------
-- 6) 回填 CONST（从 default_value 搬运）
-- ---------------------------------------------------------------
UPDATE `lp_price_variable` SET
    resolver_kind   = 'CONST',
    resolver_params = JSON_OBJECT('value', default_value)
  WHERE factor_type   = 'CONST'
    AND default_value IS NOT NULL;

-- ---------------------------------------------------------------
-- 7) 人工校验 SQL（不改数据，复制到客户端运行即可）
-- ---------------------------------------------------------------
-- (a) 各 kind 分布
-- SELECT resolver_kind, COUNT(*)
--   FROM lp_price_variable WHERE status='active'
--  GROUP BY resolver_kind;
--
-- (b) Cu / Zn / us_brass_price 的 FINANCE 参数
-- SELECT variable_code, resolver_kind, resolver_params
--   FROM lp_price_variable
--  WHERE variable_code IN ('Cu','Zn','us_brass_price');
--
-- (c) PART_CONTEXT 的 ENTITY / DERIVED 区分
-- SELECT variable_code, resolver_kind,
--        JSON_EXTRACT(resolver_params, '$.field')    AS entity_field,
--        JSON_EXTRACT(resolver_params, '$.strategy') AS derived_strategy
--   FROM lp_price_variable WHERE factor_type='PART_CONTEXT';
--
-- (d) 未回填（应当只剩 Al / Sn / Cn）
-- SELECT variable_code, factor_type
--   FROM lp_price_variable
--  WHERE status='active' AND resolver_kind IS NULL;
