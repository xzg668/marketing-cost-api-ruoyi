-- =====================================================================
-- V13: lp_price_variable 扩展 —— Task #7 (VariableRegistry)
--   tax_mode      含税/不含税声明（INCL=含税, EXCL=不含税, NONE=无税口径）
--   default_value 默认值（CONST 类型变量直接返回）
--   formula_expr  公式表达式（FORMULA_REF 类型变量递归解析其它变量）
-- 旧脚本与生产数据使用 ADD COLUMN 幂等迁移。
-- =====================================================================

ALTER TABLE `lp_price_variable`
  ADD COLUMN `tax_mode` VARCHAR(16) NOT NULL DEFAULT 'INCL'
    COMMENT '税口径: INCL=含税 / EXCL=不含税 / NONE=无税口径';

ALTER TABLE `lp_price_variable`
  ADD COLUMN `default_value` DECIMAL(20, 8) DEFAULT NULL
    COMMENT 'CONST 类型变量的默认数值';

ALTER TABLE `lp_price_variable`
  ADD COLUMN `formula_expr` VARCHAR(512) DEFAULT NULL
    COMMENT 'FORMULA_REF 类型变量的公式表达式（含 [变量] 引用）';

-- 给一组样例公式变量 seed（幂等：仅当 variable_code 不存在才插入）
INSERT IGNORE INTO `lp_price_variable`
  (`variable_code`, `variable_name`, `source_type`, `source_table`, `source_field`,
   `scope`, `status`, `tax_mode`, `default_value`, `formula_expr`,
   `created_at`, `updated_at`)
VALUES
  ('vat_rate', '增值税税率', 'CONST', NULL, NULL,
   'GLOBAL', 'active', 'NONE', 0.13000000, NULL,
   NOW(), NOW()),
  ('Cu_excl', '铜不含税基价', 'FORMULA_REF', NULL, NULL,
   'GLOBAL', 'active', 'EXCL', NULL, '[Cu] / (1 + [vat_rate])',
   NOW(), NOW());
