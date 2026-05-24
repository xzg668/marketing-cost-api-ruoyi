-- V115：产品属性系数改为系统按规则自动推导。
-- 口径：
-- 1. 标准品 / 标品 / 其他非“非标”属性：coefficient = 1.0000
-- 2. 非标准品 / 非标品：coefficient = 1.0500
-- 3. 非标准品 / 非标品且预计年用量 > 100000：coefficient = 1.0000

UPDATE lp_product_property
SET coefficient =
    CASE
      WHEN product_attr LIKE '%非标准%' OR product_attr LIKE '%非标%' THEN
        CASE
          WHEN annual_usage IS NOT NULL AND annual_usage > 100000 THEN 1.0000
          ELSE 1.0500
        END
      ELSE 1.0000
    END;
