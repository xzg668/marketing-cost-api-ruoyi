-- =====================================================================
-- V88: 回填历史影响因素到 OA 报价单基价字段的映射
--
-- 背景：
--   V85/V86 上线前已经导入的 lp_factor_identity，不会再经过导入时的
--   detectAndSaveFactorQuoteBaseMapping 流程，导致 lp_factor_quote_base_mapping
--   为空。成本试算刷新联动价时就无法判断 1#Cu/1#Zn 等应优先取 OA 表头
--   铜基价/锌基价，只能回落到月度影响因素价。
--
-- 处理：
--   仅回填当前已确认可由 OA 表头覆盖的 Cu/Zn/Al，且关键词控制得较窄，
--   避免把黄铜、磷铜、铜合金等复合材料误绑到铜基价。
-- =====================================================================

SET NAMES utf8mb4;

INSERT IGNORE INTO lp_factor_quote_base_mapping (
  factor_identity_id,
  rule_id,
  quote_field_code,
  quote_field_name,
  variable_code,
  matched_keyword,
  match_source,
  confidence,
  enabled,
  created_by,
  updated_by,
  deleted
)
SELECT
  fi.id,
  rule.id,
  rule.quote_field_code,
  rule.quote_field_name,
  rule.variable_code,
  CASE
    WHEN fi.short_name IN ('Cu', '1#Cu') THEN fi.short_name
    WHEN fi.factor_name LIKE '%电解铜%' THEN '电解铜'
    WHEN fi.factor_name LIKE '%1#铜%' THEN '1#铜'
    WHEN fi.short_name IN ('Zn', '0#Zn', '1#Zn') THEN fi.short_name
    WHEN fi.factor_name LIKE '%电解锌%' THEN '电解锌'
    WHEN fi.short_name IN ('Al', 'A00Al', 'AOOAl') THEN fi.short_name
    WHEN fi.factor_name LIKE '%A00铝%' OR fi.factor_name LIKE '%AOO铝%' THEN 'A00铝'
    WHEN fi.factor_name LIKE '%铝锭%' THEN '铝锭'
    ELSE rule.variable_code
  END AS matched_keyword,
  'AUTO',
  'HIGH',
  1,
  'migration',
  'migration',
  0
FROM lp_factor_identity fi
JOIN lp_quote_base_price_mapping_rule rule
  ON rule.deleted = 0
 AND rule.enabled = 1
 AND (
   (
     rule.variable_code = 'Cu'
     AND (
       fi.short_name IN ('Cu', '1#Cu')
       OR fi.factor_name LIKE '%电解铜%'
       OR fi.factor_name LIKE '%1#铜%'
     )
     AND fi.short_name NOT IN ('Pcu')
     AND fi.factor_name NOT LIKE '%黄铜%'
     AND fi.factor_name NOT LIKE '%磷铜%'
     AND fi.factor_name NOT LIKE '%铜合金%'
   )
   OR (
     rule.variable_code = 'Zn'
     AND (
       fi.short_name IN ('Zn', '0#Zn', '1#Zn')
       OR fi.factor_name LIKE '%电解锌%'
     )
   )
   OR (
     rule.variable_code = 'Al'
     AND (
       fi.short_name IN ('Al', 'A00Al', 'AOOAl')
       OR fi.factor_name LIKE '%A00铝%'
       OR fi.factor_name LIKE '%AOO铝%'
       OR fi.factor_name LIKE '%铝锭%'
     )
   )
 )
WHERE fi.status = 'ACTIVE';
