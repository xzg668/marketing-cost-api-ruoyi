-- =====================================================================
-- V86: 报价单基价映射基础规则 seed
--
-- 说明：
--   1) 只预置当前 OA 表头已确认存在、且单位口径已确认的 Cu/Zn/Al。
--   2) 不预置 tin_price/Sn；当前 oa_form 没有 tin_price 字段。
--   3) 关键词尽量避免过宽，例如不使用单字“铜”，防止误把黄铜、铜沫等影响因素识别成铜基价。
-- =====================================================================

SET NAMES utf8mb4;

INSERT IGNORE INTO lp_quote_base_price_mapping_rule (
  business_unit_type,
  quote_field_code,
  quote_field_name,
  variable_code,
  match_keywords_json,
  match_mode,
  priority,
  enabled,
  remark,
  created_by,
  updated_by,
  deleted
) VALUES
  (
    '',
    'copper_price',
    '铜基价',
    'Cu',
    '["铜基价","Cu基价","电解铜","1#铜","1#电解铜","长江现货1#铜","长江现货电解铜"]',
    'ANY_KEYWORD',
    10,
    1,
    'V86 seed：OA 表头铜基价覆盖 factor_identity_xxx',
    'migration',
    'migration',
    0
  ),
  (
    '',
    'zinc_price',
    '锌基价',
    'Zn',
    '["锌基价","Zn基价","0#锌","1#锌","锌锭","长江现货锌","长江现货0#锌"]',
    'ANY_KEYWORD',
    20,
    1,
    'V86 seed：OA 表头锌基价覆盖 factor_identity_xxx',
    'migration',
    'migration',
    0
  ),
  (
    '',
    'aluminum_price',
    '铝基价',
    'Al',
    '["铝基价","Al基价","A00铝","AOO铝","A00铝锭","铝锭","长江现货铝","长江现货市场AOO铝"]',
    'ANY_KEYWORD',
    30,
    1,
    'V86 seed：OA 表头铝基价覆盖 factor_identity_xxx',
    'migration',
    'migration',
    0
  );
