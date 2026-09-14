-- 制造费用率型号级规则改为“事业部::型号”，避免相同型号跨事业部串用费率。
-- 已存在新键的重复历史行保留为 LEGACY，仅退出运行时匹配，不删除业务数据。

ALTER TABLE lp_manufacture_rate
  MODIFY match_key VARCHAR(400) DEFAULT NULL COMMENT '匹配键；型号级为事业部::型号';

UPDATE lp_manufacture_rate old_rate
JOIN lp_manufacture_rate scoped_rate
  ON scoped_rate.id <> old_rate.id
 AND scoped_rate.business_unit_type = old_rate.business_unit_type
 AND scoped_rate.rate_year = old_rate.rate_year
 AND scoped_rate.match_level = 'MATERIAL_MODEL'
 AND scoped_rate.match_key = CONCAT(TRIM(old_rate.business_division), '::', TRIM(old_rate.product_model))
SET old_rate.match_level = 'LEGACY',
    old_rate.match_key = CONCAT('LEGACY-MODEL-', old_rate.id)
WHERE old_rate.match_level = 'MATERIAL_MODEL'
  AND old_rate.business_division IS NOT NULL
  AND TRIM(old_rate.business_division) <> ''
  AND old_rate.product_model IS NOT NULL
  AND TRIM(old_rate.product_model) <> ''
  AND NOT (
    old_rate.match_key
    <=> CONCAT(TRIM(old_rate.business_division), '::', TRIM(old_rate.product_model))
  );

UPDATE lp_manufacture_rate
SET match_key = CONCAT(TRIM(business_division), '::', TRIM(product_model))
WHERE match_level = 'MATERIAL_MODEL'
  AND business_division IS NOT NULL
  AND TRIM(business_division) <> ''
  AND product_model IS NOT NULL
  AND TRIM(product_model) <> ''
  AND NOT (match_key <=> CONCAT(TRIM(business_division), '::', TRIM(product_model)));

UPDATE lp_manufacture_rate
SET match_level = 'LEGACY',
    match_key = CONCAT('LEGACY-MODEL-', id)
WHERE match_level = 'MATERIAL_MODEL'
  AND (
    business_division IS NULL
    OR TRIM(business_division) = ''
    OR product_model IS NULL
    OR TRIM(product_model) = ''
  );
