-- ===========================================================
-- 测试数据包 · 00_fix_schema.sql（前置）
-- 目的：补齐 V21 迁移在本库**没有生效**的列，不然 BOM 查询全部 500
-- ===========================================================
--
-- 背景：
--   V21__business_unit_type_isolation.sql 应该给 lp_bom_manage_item 加
--   business_unit_type 列，但本库该列缺失（15:34:06 日志里抛 BadSqlGrammarException
--   "Unknown column 'business_unit_type'"）。
--
-- 这段 SQL 就是把该列补上，幂等（IF NOT EXISTS）。
--
-- 执行顺序：**最先跑**（在 01 / 02 之前）
-- ===========================================================

-- 兼容性：MySQL 8.0+ 支持 IF NOT EXISTS；如果你的库版本低，
-- 可以先 SELECT 看有没有列，再手动决定是否 ALTER。

-- 1) lp_bom_manage_item
ALTER TABLE lp_bom_manage_item
  ADD COLUMN IF NOT EXISTS business_unit_type VARCHAR(20) DEFAULT NULL
  COMMENT '业务单元：COMMERCIAL / HOUSEHOLD' AFTER oa_no;

-- 2) oa_form（V21 声明过，但也一并检查；幂等，有则跳过）
ALTER TABLE oa_form
  ADD COLUMN IF NOT EXISTS business_unit_type VARCHAR(20) DEFAULT NULL
  COMMENT '业务单元（COMMERCIAL / HOUSEHOLD）' AFTER customer;

-- 3) oa_form_item（同样是 V21 声明过，兜底补齐）
ALTER TABLE oa_form_item
  ADD COLUMN IF NOT EXISTS business_unit_type VARCHAR(20) DEFAULT NULL
  COMMENT '业务单元，冗余字段便于过滤' AFTER oa_form_id;

-- 4) lp_bom_manual_item（BOM 手工明细 / 自制件 BOM；V21 也声明过）
ALTER TABLE lp_bom_manual_item
  ADD COLUMN IF NOT EXISTS business_unit_type VARCHAR(20) DEFAULT NULL
  COMMENT '业务单元，按 bom_code 冗余' AFTER bom_code;

-- 索引补上（加速按 BU 过滤的查询）
-- 注意：MySQL 不支持 CREATE INDEX IF NOT EXISTS（那是 PostgreSQL 语法），
-- 所以用存储过程检查 INFORMATION_SCHEMA.STATISTICS 再决定是否创建，保证幂等。
DROP PROCEDURE IF EXISTS _add_idx_if_not_exists;
DELIMITER //
CREATE PROCEDURE _add_idx_if_not_exists(IN tbl VARCHAR(64), IN idx VARCHAR(64), IN cols VARCHAR(255))
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND INDEX_NAME = idx
  ) THEN
    SET @s = CONCAT('CREATE INDEX ', idx, ' ON ', tbl, ' (', cols, ')');
    PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  END IF;
END //
DELIMITER ;

CALL _add_idx_if_not_exists('lp_bom_manage_item', 'idx_bom_manage_but', 'business_unit_type');
CALL _add_idx_if_not_exists('oa_form', 'idx_oa_form_but', 'business_unit_type');
CALL _add_idx_if_not_exists('oa_form_item', 'idx_oa_form_item_but', 'business_unit_type');
CALL _add_idx_if_not_exists('lp_bom_manual_item', 'idx_bom_manual_but', 'business_unit_type');

DROP PROCEDURE _add_idx_if_not_exists;

-- ---- 历史数据回填 BU（V21 里写过的回填逻辑，这里兜底一次）----
-- oa_form：按 form_type 前缀识别
UPDATE oa_form SET business_unit_type = CASE
    WHEN form_type LIKE '家用%' THEN 'HOUSEHOLD'
    ELSE 'COMMERCIAL'
  END
WHERE business_unit_type IS NULL;

-- oa_form_item：从 oa_form 反查
UPDATE oa_form_item t
  JOIN oa_form f ON f.id = t.oa_form_id
SET t.business_unit_type = f.business_unit_type
WHERE t.business_unit_type IS NULL;

-- lp_bom_manage_item：从 oa_form 反查
UPDATE lp_bom_manage_item b
  LEFT JOIN oa_form o ON b.oa_no = o.oa_no
SET b.business_unit_type = o.business_unit_type
WHERE b.business_unit_type IS NULL
  AND o.business_unit_type IS NOT NULL;

-- ---- 验证 ----
SELECT 'oa_form' AS tbl, COUNT(*) total, COUNT(business_unit_type) with_bu,
  SUM(business_unit_type='COMMERCIAL') commercial, SUM(business_unit_type='HOUSEHOLD') household
FROM oa_form
UNION ALL
SELECT 'oa_form_item', COUNT(*), COUNT(business_unit_type),
  SUM(business_unit_type='COMMERCIAL'), SUM(business_unit_type='HOUSEHOLD')
FROM oa_form_item
UNION ALL
SELECT 'lp_bom_manage_item', COUNT(*), COUNT(business_unit_type),
  SUM(business_unit_type='COMMERCIAL'), SUM(business_unit_type='HOUSEHOLD')
FROM lp_bom_manage_item
UNION ALL
SELECT 'lp_bom_manual_item', COUNT(*), COUNT(business_unit_type),
  SUM(business_unit_type='COMMERCIAL'), SUM(business_unit_type='HOUSEHOLD')
FROM lp_bom_manual_item;
