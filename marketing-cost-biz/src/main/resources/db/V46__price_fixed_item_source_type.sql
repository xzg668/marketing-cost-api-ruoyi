-- =============================================================================
-- V46  固定价表扩展：source_type 4 桶 + 结算期间 + 结算价/采购价专属字段     2026-04-27
--
-- 强制 connection charset 为 utf8mb4，防止中文 dict_label / remark 入库乱码
SET NAMES utf8mb4;
-- =============================================================================
--
-- 业务背景（2026-04-27 业务确认）：
--   取价的本质只有 3 桶：固定价 / 联动价 / 区间价。
--   "固定价" 这一桶按物料业务分类细分为 4 类：
--     - PURCHASE 采购件   ← 来自 Excel 固定采购价5
--     - MAKE     自制件   ← 来自 Excel 自制件4
--     - SETTLE   结算价   ← 来自 Excel 家用结算价9 的"基准结算价"列
--     - SCRAP    废料     ← 来自 Excel 原材料(联动+固定-7) R14-R16（订单类型=固定）
--   按月切版本：pricing_month YYYY-MM 锁结算期间快照
--
-- 本脚本职责：
--   1) 加 6 个字段：source_type / process_no / planned_price / markup_ratio / remark / pricing_month
--   2) 老 3 行数据回填默认值（source_type=PURCHASE, pricing_month=2026-03）
--   3) 重做 UK：(material_code, supplier_code, business_unit_type, source_type, pricing_month)
--      —— 同料号 + 不同供方 + 不同 BU + 不同来源 + 不同月份都能并存
--   4) 字典 lp_fixed_price_source_type 注册（业务后续在 /system/dict 自助加新类型）
--
-- 不动：
--   - 老字段保留（formula_expr / blank_weight / net_weight / process_fee / agent_fee / order_type / quota）
--   - effective_from / effective_to 保留（pricing_month 是月度锚点，effective_* 仍可表达月内细粒度有效区间）
--
-- 幂等：所有 ALTER 用 information_schema 检查；UPDATE 带 WHERE col IS NULL 兜底
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1) 加字段（用存储过程检查列是否存在，MySQL 8 不支持 ADD COLUMN IF NOT EXISTS）
-- -----------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS v46_add_columns;
DELIMITER $$
CREATE PROCEDURE v46_add_columns()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='source_type') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN source_type VARCHAR(32) NOT NULL DEFAULT 'PURCHASE'
        COMMENT '来源类型：PURCHASE采购件 / MAKE自制件 / SETTLE结算价 / SCRAP废料';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='process_no') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN process_no VARCHAR(64) NULL COMMENT '采购流程编号（PURCHASE 来源专用）';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='planned_price') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN planned_price DECIMAL(12,6) NULL COMMENT '计划价（SETTLE 来源专用；fixed_price = planned × markup）';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='markup_ratio') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN markup_ratio DECIMAL(10,6) NULL COMMENT '上浮比例（SETTLE 来源专用）';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='remark') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN remark VARCHAR(512) NULL COMMENT '备注';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='pricing_month') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN pricing_month VARCHAR(7) NOT NULL DEFAULT '2026-03'
        COMMENT '结算期间 YYYY-MM（按月切版本，每月一份固定价快照）';
  END IF;
END$$
DELIMITER ;
CALL v46_add_columns();
DROP PROCEDURE v46_add_columns;

-- -----------------------------------------------------------------------------
-- 2) 老数据回填：source_type 已有 DEFAULT 自动填 PURCHASE；pricing_month 同理填 2026-03
--    显式 UPDATE NULL 行兜底（防 DEFAULT 在某些环境不生效）
-- -----------------------------------------------------------------------------
UPDATE lp_price_fixed_item SET source_type='PURCHASE' WHERE source_type IS NULL OR source_type='';
UPDATE lp_price_fixed_item SET pricing_month='2026-03' WHERE pricing_month IS NULL OR pricing_month='';

-- -----------------------------------------------------------------------------
-- 3) 重做 UK：先尝试删老 UK（如果存在），再加新 UK
--    注：旧表只有 PRIMARY，没有业务 UK；此段为未来可能存在的 UK 兜底
-- -----------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS v46_rebuild_uk;
DELIMITER $$
CREATE PROCEDURE v46_rebuild_uk()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item'
                   AND index_name='uk_fixed_unique') THEN
    ALTER TABLE lp_price_fixed_item
      ADD UNIQUE KEY uk_fixed_unique
        (material_code, supplier_code, business_unit_type, source_type, pricing_month);
  END IF;
END$$
DELIMITER ;
CALL v46_rebuild_uk();
DROP PROCEDURE v46_rebuild_uk;

-- -----------------------------------------------------------------------------
-- 4) source_type 字典化（业务在 /system/dict 自助维护未来新增的来源类型）
-- -----------------------------------------------------------------------------
INSERT INTO sys_dict_type (dict_name, dict_type, status, remark)
  SELECT '固定价来源类型', 'lp_fixed_price_source_type', '0',
         'V46 新增：固定价表 source_type 枚举（采购/自制/结算/废料 4 桶）'
  FROM DUAL
  WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type='lp_fixed_price_source_type');

-- 兜住第一次 apply 时 charset 不是 utf8mb4 导致中文乱码
UPDATE sys_dict_type SET dict_name='固定价来源类型'
  WHERE dict_type='lp_fixed_price_source_type' AND dict_name<>'固定价来源类型';

INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 1, '采购件', 'PURCHASE', 'lp_fixed_price_source_type', '0', '来自固定采购价 sheet'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data
                              WHERE dict_type='lp_fixed_price_source_type' AND dict_value='PURCHASE');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 2, '自制件', 'MAKE', 'lp_fixed_price_source_type', '0', '来自自制件 sheet'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data
                              WHERE dict_type='lp_fixed_price_source_type' AND dict_value='MAKE');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 3, '结算价', 'SETTLE', 'lp_fixed_price_source_type', '0', '来自家用结算价 sheet 的基准结算价'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data
                              WHERE dict_type='lp_fixed_price_source_type' AND dict_value='SETTLE');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 4, '废料', 'SCRAP', 'lp_fixed_price_source_type', '0', '来自原材料(联动+固定) sheet 订单类型=固定 行'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data
                              WHERE dict_type='lp_fixed_price_source_type' AND dict_value='SCRAP');

-- V46 结束
