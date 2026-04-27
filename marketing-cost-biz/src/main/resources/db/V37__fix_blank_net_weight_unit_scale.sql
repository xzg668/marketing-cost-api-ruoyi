-- =====================================================================
-- V37: 修正 blank_weight / net_weight 的 unitScale，消除双重换算 bug
--
-- 背景（2026-04-22 OA-GOLDEN-001 trace 发现）：
--   对 SHF-AA-79 的 10 条联动价公式，dual 模式跑出
--     legacy 结果 = 3.3236（与 Excel 金标一致）
--     new    结果 = 0.3080（差 1000 倍）
--
--   根因：V24 给 blank_weight / net_weight 配了 context_binding_json.unitScale = 0.001
--   （"单位 g→kg 需 ×0.001"），但历史公式（和 OA-GOLDEN-001 用的公式）是
--   **g 语义**——公式里**自带 /1000** 换算（例：
--     [blank_weight]*([Cu]*0.62/0.98+…)/1000 - ([blank_weight]-[net_weight])*…*0.92/1000
--   ），公式作者本意是代入 DB 里的 g 原值（例 70、40），让公式自己 /1000 → kg。
--
--   V24 的 unitScale 让 new 管线先把值 ×0.001 变 kg，公式再 /1000 一次 →
--   多除一个 1000 → 结果偏小 1000 倍。legacy 管线不读 context_binding_json，
--   保持 g 原值代入，所以 legacy 对了金标。
--
-- 方案选择（权衡见 plan-b 讨论）：
--   A. 全库清洗公式去 /1000 + DB 存 kg   —— 改动大、风险高
--   B. unitScale 0.001 → 1.0            —— **本 migration**，1 条 UPDATE，零风险
--   C. 锁 parser.mode=legacy            —— 放弃 new 管线，长期负债
--
-- 选 B：unitScale 恢复 1.0，让 new 管线和 legacy 都拿 DB 原值（g），
-- 两条管线对同一公式算出同值 → dual diff=0 → 未来切 new 模式无缝。
--
-- 需要同时修两个 JSON 列（V24 的 context_binding_json 和 V31 的 resolver_params）：
--   两列都被 registry 的 ENTITY 分支路径引用到。
--
-- 注意：**改完必须重启后端**（或调 /admin/variables/cache/refresh）清 registry 的
-- variableCache 懒加载缓存，否则进程内仍是旧 unitScale。
--
-- 幂等：JSON_SET 覆盖式写入，重跑结果一致。
-- 回滚：如需恢复 0.001，把下面 `1.0` 改回 `0.001` 重跑即可（数据不丢）。
-- =====================================================================

SET NAMES utf8mb4;

-- 1) V24 的 context_binding_json —— 把 unitScale 改回 1.0
UPDATE lp_price_variable
SET context_binding_json = JSON_SET(
  context_binding_json,
  '$.unitScale', 1.0
)
WHERE variable_code IN ('blank_weight', 'net_weight')
  AND context_binding_json IS NOT NULL
  AND JSON_EXTRACT(context_binding_json, '$.unitScale') IS NOT NULL;

-- 2) V31 的 resolver_params —— 回填时从 context_binding_json 拷了一份，也要同步
UPDATE lp_price_variable
SET resolver_params = JSON_SET(
  resolver_params,
  '$.unitScale', 1.0
)
WHERE variable_code IN ('blank_weight', 'net_weight')
  AND resolver_params IS NOT NULL
  AND JSON_EXTRACT(resolver_params, '$.unitScale') IS NOT NULL;

-- ---- 验证 ----
-- 期望：两列都应该是 1.0，而不是 0.001
SELECT
  variable_code,
  JSON_EXTRACT(context_binding_json, '$.unitScale') AS ctx_scale,
  JSON_EXTRACT(resolver_params, '$.unitScale')      AS resolver_scale
FROM lp_price_variable
WHERE variable_code IN ('blank_weight', 'net_weight');
