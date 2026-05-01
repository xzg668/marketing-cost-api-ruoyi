-- =============================================================================
-- V48  固定价表瘦身：MAKE 迁到 lp_make_part_spec / SCRAP 迁到 lp_price_scrap   2026-04-28
--
-- 强制 connection charset 为 utf8mb4
SET NAMES utf8mb4;
-- =============================================================================
--
-- 业务背景（2026-04-28，多轮讨论后定型）：
--   V46 把 4 类来源（PURCHASE/MAKE/SETTLE/SCRAP）挤在 lp_price_fixed_item 单表。
--   重新审视后：
--     - 自制件（MAKE）不是"一口价"，是按配方实时算（毛重×料价 - 废料 + 加工费），
--       已有 lp_make_part_spec 表专门承载（含 raw_material/recycle/process_fee/formula_id）；
--     - 废料（SCRAP）不是"成品价"，是回收价（业务收入侧），独立表 lp_price_scrap；
--     - 固定价表的本质 = 财务一口价：保留 PURCHASE 17 + SETTLE 57，共 74 条。
--
-- 本脚本职责：
--   1) 重建 lp_price_scrap 表（之前 V48 草版建过又被清掉，这次定型）
--   2) MAKE 6 条 lp_price_fixed_item → lp_make_part_spec
--      字段映射：material_code/blank_weight/net_weight/process_fee 直接对应；
--                 fixed_price 暂不映射（make 走公式算，没用 fixed_price）；
--                 raw_material_*/recycle_* 留 NULL，业务后续在自制件页补
--   3) SCRAP 4 条 lp_price_fixed_item → lp_price_scrap
--      字段映射：material_code/material_name/fixed_price → recycle_price
--   4) 从 lp_price_fixed_item 删除 MAKE/SCRAP 共 10 行（V48 已备份到目标表）
--
-- 不动：
--   - PURCHASE / SETTLE 行留 lp_price_fixed_item（共 74 条）
--   - source_type 字段保留（现在只有 PURCHASE/SETTLE 两个值；前端 tab 切流仍用它）
--
-- 幂等：CREATE TABLE IF NOT EXISTS + INSERT IGNORE + DELETE 带 source_type 过滤
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1) 重建 lp_price_scrap 表（废料回收价）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS lp_price_scrap (
  id                  BIGINT NOT NULL AUTO_INCREMENT,
  business_unit_type  VARCHAR(20)  NULL COMMENT 'V21 业务单元隔离',
  pricing_month       CHAR(7)      NOT NULL COMMENT '结算期间 YYYY-MM',
  scrap_code          VARCHAR(64)  NOT NULL COMMENT '废料代号（业务唯一标识，如 废紫铜/废不锈钢SUS304）',
  scrap_name          VARCHAR(128) NULL COMMENT '废料名称',
  spec_model          VARCHAR(128) NULL COMMENT '规格型号',
  unit                VARCHAR(16)  NULL COMMENT '单位',
  recycle_price       DECIMAL(18,6) NULL COMMENT '回收单价（业务收入侧，不是采购成本）',
  tax_included        TINYINT      NULL DEFAULT 1 COMMENT '是否含税',
  effective_from      DATE         NULL,
  effective_to        DATE         NULL,
  remark              VARCHAR(512) NULL,
  deleted             TINYINT      NOT NULL DEFAULT 0,
  created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_scrap_month_code_bu (pricing_month, scrap_code, business_unit_type),
  KEY idx_scrap_code (scrap_code),
  KEY idx_scrap_bu (business_unit_type),
  KEY idx_scrap_deleted (deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='废料回收价（与 lp_finance_base_price 互补）';

-- -----------------------------------------------------------------------------
-- 2) MAKE 6 条 → lp_make_part_spec
--    period 用旧表的 pricing_month；其他业务字段留 NULL 让业务在自制件页面补
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO lp_make_part_spec
  (material_code, material_name, period, blank_weight, net_weight, process_fee,
   effective_from, effective_to, remark, created_at, updated_at)
SELECT
  material_code, material_name, pricing_month, blank_weight, net_weight, process_fee,
  effective_from, effective_to, remark, created_at, updated_at
FROM lp_price_fixed_item
WHERE source_type='MAKE';

-- -----------------------------------------------------------------------------
-- 3) SCRAP 4 条 → lp_price_scrap
--    material_code → scrap_code；material_name → scrap_name；fixed_price → recycle_price
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO lp_price_scrap
  (business_unit_type, pricing_month, scrap_code, scrap_name, spec_model, unit,
   recycle_price, tax_included, effective_from, effective_to, remark, created_at, updated_at)
SELECT
  business_unit_type, pricing_month, material_code, material_name, spec_model, unit,
  fixed_price, tax_included, effective_from, effective_to, remark, created_at, updated_at
FROM lp_price_fixed_item
WHERE source_type='SCRAP';

-- -----------------------------------------------------------------------------
-- 4) 从 lp_price_fixed_item 删除 MAKE/SCRAP 10 行（已备份到目标表）
-- -----------------------------------------------------------------------------
DELETE FROM lp_price_fixed_item WHERE source_type IN ('MAKE','SCRAP');

-- =============================================================================
-- 验证：
--   lp_price_fixed_item 应剩 PURCHASE 17 + SETTLE 57 = 74
--   lp_make_part_spec  +6 行（之前 0，现在 6）
--   lp_price_scrap     +4 行（新表，4 行）
-- =============================================================================
