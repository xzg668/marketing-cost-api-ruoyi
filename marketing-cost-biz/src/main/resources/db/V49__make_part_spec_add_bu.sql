-- =============================================================================
-- V49  自制件表补加 business_unit_type 列                                  2026-04-28
--
-- 强制 connection charset 为 utf8mb4
SET NAMES utf8mb4;
-- =============================================================================
--
-- 业务背景：
--   lp_make_part_spec 表（早期建的）没有 business_unit_type 列，但
--   MakePartSpec entity 里声明了 businessUnitType + DataScope 拦截器自动给
--   selectList/selectPage 加 WHERE business_unit_type=? —— V48 暴露 UI 后
--   list 接口直接 SQL 报错 "Unknown column 'business_unit_type'"。
--
-- 本脚本职责：
--   1) ALTER TABLE 加 business_unit_type 列 + 索引
--   2) 已有行回填 BU（V48 迁来的 6 行都来自 COMMERCIAL）
--
-- 幂等：用存储过程检查列是否存在
-- =============================================================================

DROP PROCEDURE IF EXISTS v49_add_bu_column;
DELIMITER $$
CREATE PROCEDURE v49_add_bu_column()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_make_part_spec'
                 AND column_name='business_unit_type') THEN
    ALTER TABLE lp_make_part_spec
      ADD COLUMN business_unit_type VARCHAR(20) NULL COMMENT 'V21 业务单元数据隔离' AFTER id,
      ADD INDEX idx_make_spec_bu (business_unit_type);
  END IF;
END$$
DELIMITER ;

CALL v49_add_bu_column();
DROP PROCEDURE v49_add_bu_column;

-- 已有行回填（仅 NULL 行）；V48 迁来的 MAKE 数据来自 COMMERCIAL
UPDATE lp_make_part_spec SET business_unit_type='COMMERCIAL' WHERE business_unit_type IS NULL;
