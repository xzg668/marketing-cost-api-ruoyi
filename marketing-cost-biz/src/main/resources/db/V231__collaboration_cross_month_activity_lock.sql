-- 同一组织、同一产品的补录任务改为跨报价、跨月份唯一。
-- 有料号按料号锁；无料号按型号锁；无料号且无型号才按稳定临时键锁。
-- 不新增表或字段，只把仍在进行中的 V2 锁键迁移为 V3；终态继续保持 NULL。

SET NAMES utf8mb4;

UPDATE lp_quote_collaboration_product_task
SET active_lock_key = CONCAT(
    'QCBP-ACTIVE-V3:',
    SHA2(CONCAT(
        'VERSION=3', CHAR(10),
        'IDENTITY_TYPE=', CASE
          WHEN NULLIF(TRIM(product_code), '') IS NOT NULL THEN 'PRODUCT'
          WHEN NULLIF(TRIM(product_model), '') IS NOT NULL THEN 'MODEL'
          ELSE 'TEMP'
        END, CHAR(10),
        'IDENTITY=', UPPER(CASE
          WHEN NULLIF(TRIM(product_code), '') IS NOT NULL THEN TRIM(product_code)
          WHEN NULLIF(TRIM(product_model), '') IS NOT NULL THEN TRIM(product_model)
          ELSE TRIM(temporary_product_key)
        END), CHAR(10),
        'BUSINESS_UNIT=', UPPER(TRIM(business_unit_type)), CHAR(10),
        'ORG=', UPPER(TRIM(applicable_org_code))
    ), 256)
)
WHERE active_flag = 1
  AND NULLIF(TRIM(business_unit_type), '') IS NOT NULL
  AND NULLIF(TRIM(applicable_org_code), '') IS NOT NULL
  AND COALESCE(
      NULLIF(TRIM(product_code), ''),
      NULLIF(TRIM(product_model), ''),
      NULLIF(TRIM(temporary_product_key), '')
  ) IS NOT NULL;

UPDATE lp_quote_collaboration_product_task
SET active_lock_key = NULL
WHERE active_flag = 0
  AND active_lock_key IS NOT NULL;
