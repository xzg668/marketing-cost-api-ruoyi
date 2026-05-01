-- =============================================================================
-- 修复 marketing_cost 库 mojibake 数据（utf8 → latin1 → utf8mb4 二次编码污染）
-- 日期：2026-04-29
-- 范围：5 表 16 列 ~166 行
-- 策略：先备份原 binary 字节到 _moji_backup_20260429，再 UPDATE
-- 回滚：UPDATE table SET col = original_bytes FROM _moji_backup_20260429 WHERE ...
-- 安全：所有 UPDATE 都带严 WHERE 只动 mojibake 行，绝不动正常 utf8mb4 中文行
-- =============================================================================

-- 备份表（原 mojibake 字节存为 VARBINARY，避免再次字符集转换破坏）
DROP TABLE IF EXISTS _moji_backup_20260429;
CREATE TABLE _moji_backup_20260429 (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  table_name VARCHAR(64) NOT NULL,
  col_name VARCHAR(64) NOT NULL,
  pk_value VARCHAR(64) NOT NULL COMMENT '受影响行的主键',
  original_hex TEXT NOT NULL COMMENT '原 binary 字节 hex 表示',
  fixed_value TEXT NOT NULL COMMENT '修复后的 utf8mb4 字符串（dry-run 结果）',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='2026-04-29 mojibake 修复备份';

-- mojibake 检测条件复用（要求 hex 含 ≥3 段 (C2|C3|C5)?? 或 E280?? 或 CB8?-CBF? 连续）
-- 用 REGEXP 在 HEX(col) 字符串上匹配
SET @MOJI_PATTERN = '((C2|C3|C5)[0-9A-F]{2}|E280[0-9A-F]{2}|CB[8-9A-F][0-9A-F]){3,}';

-- ============================================================
-- 1) oa_form (4 列 各 1 行)
-- ============================================================
INSERT INTO _moji_backup_20260429 (table_name, col_name, pk_value, original_hex, fixed_value)
SELECT 'oa_form', 'customer', id, HEX(customer),
       CONVERT(BINARY(CONVERT(customer USING latin1)) USING utf8mb4)
FROM oa_form WHERE HEX(customer) REGEXP @MOJI_PATTERN;

INSERT INTO _moji_backup_20260429 (table_name, col_name, pk_value, original_hex, fixed_value)
SELECT 'oa_form', 'form_type', id, HEX(form_type),
       CONVERT(BINARY(CONVERT(form_type USING latin1)) USING utf8mb4)
FROM oa_form WHERE HEX(form_type) REGEXP @MOJI_PATTERN;

INSERT INTO _moji_backup_20260429 (table_name, col_name, pk_value, original_hex, fixed_value)
SELECT 'oa_form', 'remark', id, HEX(remark),
       CONVERT(BINARY(CONVERT(remark USING latin1)) USING utf8mb4)
FROM oa_form WHERE HEX(remark) REGEXP @MOJI_PATTERN;

INSERT INTO _moji_backup_20260429 (table_name, col_name, pk_value, original_hex, fixed_value)
SELECT 'oa_form', 'sale_link', id, HEX(sale_link),
       CONVERT(BINARY(CONVERT(sale_link USING latin1)) USING utf8mb4)
FROM oa_form WHERE HEX(sale_link) REGEXP @MOJI_PATTERN;

UPDATE oa_form SET customer  = CONVERT(BINARY(CONVERT(customer  USING latin1)) USING utf8mb4) WHERE HEX(customer)  REGEXP @MOJI_PATTERN;
UPDATE oa_form SET form_type = CONVERT(BINARY(CONVERT(form_type USING latin1)) USING utf8mb4) WHERE HEX(form_type) REGEXP @MOJI_PATTERN;
UPDATE oa_form SET remark    = CONVERT(BINARY(CONVERT(remark    USING latin1)) USING utf8mb4) WHERE HEX(remark)    REGEXP @MOJI_PATTERN;
UPDATE oa_form SET sale_link = CONVERT(BINARY(CONVERT(sale_link USING latin1)) USING utf8mb4) WHERE HEX(sale_link) REGEXP @MOJI_PATTERN;

-- ============================================================
-- 2) oa_form_item (2 列 各 1 行)
-- ============================================================
INSERT INTO _moji_backup_20260429 (table_name, col_name, pk_value, original_hex, fixed_value)
SELECT 'oa_form_item', 'product_name', id, HEX(product_name),
       CONVERT(BINARY(CONVERT(product_name USING latin1)) USING utf8mb4)
FROM oa_form_item WHERE HEX(product_name) REGEXP @MOJI_PATTERN;

INSERT INTO _moji_backup_20260429 (table_name, col_name, pk_value, original_hex, fixed_value)
SELECT 'oa_form_item', 'spec', id, HEX(spec),
       CONVERT(BINARY(CONVERT(spec USING latin1)) USING utf8mb4)
FROM oa_form_item WHERE HEX(spec) REGEXP @MOJI_PATTERN;

UPDATE oa_form_item SET product_name = CONVERT(BINARY(CONVERT(product_name USING latin1)) USING utf8mb4) WHERE HEX(product_name) REGEXP @MOJI_PATTERN;
UPDATE oa_form_item SET spec         = CONVERT(BINARY(CONVERT(spec         USING latin1)) USING utf8mb4) WHERE HEX(spec)         REGEXP @MOJI_PATTERN;

-- ============================================================
-- 3) lp_bom_manage_item (7 列 ~150 行) —— 受影响最多
-- ============================================================
INSERT INTO _moji_backup_20260429 (table_name, col_name, pk_value, original_hex, fixed_value)
SELECT 'lp_bom_manage_item', 'customer_name', id, HEX(customer_name),
       CONVERT(BINARY(CONVERT(customer_name USING latin1)) USING utf8mb4)
FROM lp_bom_manage_item WHERE HEX(customer_name) REGEXP @MOJI_PATTERN;

INSERT INTO _moji_backup_20260429 (table_name, col_name, pk_value, original_hex, fixed_value)
SELECT 'lp_bom_manage_item', 'item_name', id, HEX(item_name),
       CONVERT(BINARY(CONVERT(item_name USING latin1)) USING utf8mb4)
FROM lp_bom_manage_item WHERE HEX(item_name) REGEXP @MOJI_PATTERN;

INSERT INTO _moji_backup_20260429 (table_name, col_name, pk_value, original_hex, fixed_value)
SELECT 'lp_bom_manage_item', 'item_spec', id, HEX(item_spec),
       CONVERT(BINARY(CONVERT(item_spec USING latin1)) USING utf8mb4)
FROM lp_bom_manage_item WHERE HEX(item_spec) REGEXP @MOJI_PATTERN;

INSERT INTO _moji_backup_20260429 (table_name, col_name, pk_value, original_hex, fixed_value)
SELECT 'lp_bom_manage_item', 'material', id, HEX(material),
       CONVERT(BINARY(CONVERT(material USING latin1)) USING utf8mb4)
FROM lp_bom_manage_item WHERE HEX(material) REGEXP @MOJI_PATTERN;

INSERT INTO _moji_backup_20260429 (table_name, col_name, pk_value, original_hex, fixed_value)
SELECT 'lp_bom_manage_item', 'product_name', id, HEX(product_name),
       CONVERT(BINARY(CONVERT(product_name USING latin1)) USING utf8mb4)
FROM lp_bom_manage_item WHERE HEX(product_name) REGEXP @MOJI_PATTERN;

INSERT INTO _moji_backup_20260429 (table_name, col_name, pk_value, original_hex, fixed_value)
SELECT 'lp_bom_manage_item', 'product_spec', id, HEX(product_spec),
       CONVERT(BINARY(CONVERT(product_spec USING latin1)) USING utf8mb4)
FROM lp_bom_manage_item WHERE HEX(product_spec) REGEXP @MOJI_PATTERN;

INSERT INTO _moji_backup_20260429 (table_name, col_name, pk_value, original_hex, fixed_value)
SELECT 'lp_bom_manage_item', 'shape_attr', id, HEX(shape_attr),
       CONVERT(BINARY(CONVERT(shape_attr USING latin1)) USING utf8mb4)
FROM lp_bom_manage_item WHERE HEX(shape_attr) REGEXP @MOJI_PATTERN;

UPDATE lp_bom_manage_item SET customer_name = CONVERT(BINARY(CONVERT(customer_name USING latin1)) USING utf8mb4) WHERE HEX(customer_name) REGEXP @MOJI_PATTERN;
UPDATE lp_bom_manage_item SET item_name     = CONVERT(BINARY(CONVERT(item_name     USING latin1)) USING utf8mb4) WHERE HEX(item_name)     REGEXP @MOJI_PATTERN;
UPDATE lp_bom_manage_item SET item_spec     = CONVERT(BINARY(CONVERT(item_spec     USING latin1)) USING utf8mb4) WHERE HEX(item_spec)     REGEXP @MOJI_PATTERN;
UPDATE lp_bom_manage_item SET material      = CONVERT(BINARY(CONVERT(material      USING latin1)) USING utf8mb4) WHERE HEX(material)      REGEXP @MOJI_PATTERN;
UPDATE lp_bom_manage_item SET product_name  = CONVERT(BINARY(CONVERT(product_name  USING latin1)) USING utf8mb4) WHERE HEX(product_name)  REGEXP @MOJI_PATTERN;
UPDATE lp_bom_manage_item SET product_spec  = CONVERT(BINARY(CONVERT(product_spec  USING latin1)) USING utf8mb4) WHERE HEX(product_spec)  REGEXP @MOJI_PATTERN;
UPDATE lp_bom_manage_item SET shape_attr    = CONVERT(BINARY(CONVERT(shape_attr    USING latin1)) USING utf8mb4) WHERE HEX(shape_attr)    REGEXP @MOJI_PATTERN;

-- ============================================================
-- 4) lp_cost_run_result (2 列 各 1 行)
-- ============================================================
INSERT INTO _moji_backup_20260429 (table_name, col_name, pk_value, original_hex, fixed_value)
SELECT 'lp_cost_run_result', 'customer_name', id, HEX(customer_name),
       CONVERT(BINARY(CONVERT(customer_name USING latin1)) USING utf8mb4)
FROM lp_cost_run_result WHERE HEX(customer_name) REGEXP @MOJI_PATTERN;

INSERT INTO _moji_backup_20260429 (table_name, col_name, pk_value, original_hex, fixed_value)
SELECT 'lp_cost_run_result', 'product_name', id, HEX(product_name),
       CONVERT(BINARY(CONVERT(product_name USING latin1)) USING utf8mb4)
FROM lp_cost_run_result WHERE HEX(product_name) REGEXP @MOJI_PATTERN;

UPDATE lp_cost_run_result SET customer_name = CONVERT(BINARY(CONVERT(customer_name USING latin1)) USING utf8mb4) WHERE HEX(customer_name) REGEXP @MOJI_PATTERN;
UPDATE lp_cost_run_result SET product_name  = CONVERT(BINARY(CONVERT(product_name  USING latin1)) USING utf8mb4) WHERE HEX(product_name)  REGEXP @MOJI_PATTERN;

-- ============================================================
-- 5) bom_stop_drill_rule (1 列 1 行 半污染)
-- ============================================================
-- 半污染特殊处理：T11停用-业务废弃 部分是 mojibake，前面 "T8 示例：..." 是正常 utf8。
-- 我们的修复链对**整个字段**走 latin1 转换，正常 utf8 部分会被 latin1 转换成 '?' 损坏。
-- 这条不能用通用方式修，单独处理：先备份观察，UPDATE 跳过。
INSERT INTO _moji_backup_20260429 (table_name, col_name, pk_value, original_hex, fixed_value)
SELECT 'bom_stop_drill_rule', 'remark', id, HEX(remark), '<<半污染需手工修复>>'
FROM bom_stop_drill_rule WHERE HEX(remark) REGEXP @MOJI_PATTERN;
-- 不 UPDATE，留给手工处理（避免破坏前半正常中文）。

-- ============================================================
-- 验证：备份了多少行 / 修复后再扫还有多少 mojibake
-- ============================================================
SELECT '修复备份行数' AS metric, COUNT(*) AS value FROM _moji_backup_20260429
UNION ALL
SELECT 'oa_form 残留 customer', COUNT(*) FROM oa_form WHERE HEX(customer) REGEXP @MOJI_PATTERN
UNION ALL
SELECT 'oa_form 残留 form_type', COUNT(*) FROM oa_form WHERE HEX(form_type) REGEXP @MOJI_PATTERN
UNION ALL
SELECT 'lp_bom_manage_item 残留 customer_name', COUNT(*) FROM lp_bom_manage_item WHERE HEX(customer_name) REGEXP @MOJI_PATTERN
UNION ALL
SELECT 'lp_bom_manage_item 残留 product_name', COUNT(*) FROM lp_bom_manage_item WHERE HEX(product_name) REGEXP @MOJI_PATTERN;

-- 修复后样本展示
SELECT 'oa_form.customer' AS field, customer FROM oa_form WHERE oa_no='OA-GOLDEN-001'
UNION ALL
SELECT 'oa_form.form_type', form_type FROM oa_form WHERE oa_no='OA-GOLDEN-001'
UNION ALL
SELECT 'lp_bom_manage_item.customer_name (top 1)', customer_name FROM lp_bom_manage_item LIMIT 1;
