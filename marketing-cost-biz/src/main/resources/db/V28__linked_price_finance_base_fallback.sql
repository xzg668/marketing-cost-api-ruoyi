-- =====================================================================
-- V28: 联动价改造 —— 金属基价走 FINANCE_BASE + 数据规约化（T26c）
--
-- 背景：T26 E2E 发现 /formula/preview 对 Cu/Zn/Sn/Al/Cn 返回 MISSING。
-- 根因两条：
--   1) 这些变量 source_type=OA_FORM，预览态无 OA 上下文 → OaResolver 永 null
--   2) 业务真实规则："OA 有则用 OA 锁价，无 OA 回落到基价表月均价"
--      原架构没有这条 fallback 链 —— Cu/Zn 没 OA 就返 0
--
-- 业务真相核对（来自 产品成本计算表.xls "金属原材料价格" sheet）：
--   - 电解铜本月 90.00 → 对应 lp_finance_base_price id=7（长江现货，90.000000）
--   - 锌本月 21.6836   → 对应 lp_finance_base_price id=8（SMM，21.680000；DB 无长江现货数据）
--   - 铝/铜黄铜等均来自长江现货
--   → 权威源 = "长江现货平均价"；SMM 仅为审计/对账存档
--
-- 做法（方案：ctx.overrides 承载 OA 锁价 + FinanceBaseResolver 兜底查基价表）：
--   1) 规约化 lp_finance_base_price.price_source：
--        "平均价"（模糊）→ "长江现货平均价" / "SMM平均价"（按 factor_name 精确分类）
--   2) BU NULL → COMMERCIAL（dev 清理；生产导入服务已改为从 JWT 强制填）
--   3) Cu/Zn/Sn/Al/Cn 的 source_type 从 OA_FORM 改为 FINANCE_BASE，
--      context_binding_json 写入 {factorCode, priceSource="长江现货平均价"}
--   4) 加复合索引加速 (factor_code, price_month, price_source, business_unit_type) 四键精确查
--
-- OA 锁价路径：上提到 Calc/Preview 服务层 —— 构造 VariableContext 时展开 oaForm
-- 到 ctx.overrides（优先级最高，见 VariableRegistry:106）。OaResolver 保留代码
-- 不动，便于向后兼容；只是此后无变量的 source_type 是 OA_FORM，不会被路由到。
--
-- 关于"无 Zn 长江现货数据"的故意暴露：
--   变量 Zn 绑 priceSource="长江现货平均价"，但 DB 里 Zn 只有 SMM 数据。
--   FinanceBaseResolver 查不到 → 返 MISSING，将"财务未按权威源导入 Zn"这个
--   问题暴露到 preview 结果页，由业务补数据；绝不自动降级到 SMM 数据兜底。
-- =====================================================================

-- -----------------------------------------------------------------------
-- 1) 规约化 price_source：将"平均价"按 factor_name 关键字拆成权威源/SMM
-- -----------------------------------------------------------------------
UPDATE lp_finance_base_price
SET price_source = '长江现货平均价'
WHERE price_source = '平均价'
  AND factor_name LIKE '%长江现货%';

UPDATE lp_finance_base_price
SET price_source = 'SMM平均价'
WHERE price_source = '平均价'
  AND (factor_name LIKE '%SMM%' OR factor_name LIKE '%上海有色网%');

-- -----------------------------------------------------------------------
-- 2) BU NULL 回填 —— 仅 dev 历史数据清理；生产导入服务已强制从 JWT 填 BU
-- -----------------------------------------------------------------------
UPDATE lp_finance_base_price
SET business_unit_type = 'COMMERCIAL'
WHERE business_unit_type IS NULL;

-- -----------------------------------------------------------------------
-- 3) 金属基价变量改 source_type = FINANCE_BASE，写入 context_binding_json
--    priceSource 默认绑"长江现货平均价"（业务真相：Excel 金属原材料价格 sheet
--    取的是这个源；若未来某变量需要改用 SMM，只改这一条 JSON 不动代码）
-- -----------------------------------------------------------------------
UPDATE lp_price_variable
SET source_type = 'FINANCE_BASE',
    context_binding_json = '{"factorCode":"Cu","priceSource":"长江现货平均价"}'
WHERE variable_code = 'Cu';

UPDATE lp_price_variable
SET source_type = 'FINANCE_BASE',
    context_binding_json = '{"factorCode":"Zn","priceSource":"长江现货平均价"}'
WHERE variable_code = 'Zn';

UPDATE lp_price_variable
SET source_type = 'FINANCE_BASE',
    context_binding_json = '{"factorCode":"Al","priceSource":"长江现货平均价"}'
WHERE variable_code = 'Al';

UPDATE lp_price_variable
SET source_type = 'FINANCE_BASE',
    context_binding_json = '{"factorCode":"Sn","priceSource":"长江现货平均价"}'
WHERE variable_code = 'Sn';

UPDATE lp_price_variable
SET source_type = 'FINANCE_BASE',
    context_binding_json = '{"factorCode":"Cn","priceSource":"长江现货平均价"}'
WHERE variable_code = 'Cn';

-- -----------------------------------------------------------------------
-- 4) 四键复合索引 —— FinanceBaseResolver 按此精确查
--    不加 UNIQUE 因为历史 dev 数据仍有 (Zn, 2026-02, SMM平均价) 重复行，
--    唯一性由 import 服务的 UPSERT 逻辑保障（V29 计划），不绑到 DB 约束。
-- -----------------------------------------------------------------------
CREATE INDEX idx_fin_base_4key
  ON lp_finance_base_price (factor_code, price_month, price_source, business_unit_type);
