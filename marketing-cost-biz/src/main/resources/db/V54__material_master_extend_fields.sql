-- =============================================================================
-- V54  扩 lp_material_master 字段（容纳 U9 ItemMaster 关键元数据）       2026-04-28
--
SET NAMES utf8mb4;
-- =============================================================================
--
-- 业务背景：
--   V53 把 16.3 万条 U9 ItemMaster 全量进了 staging 表 lp_material_master_raw。
--   主表 lp_material_master 只装"业务真正用到的"料号（按需同步），
--   但需要先把字段扩齐 —— 把核心元数据列（成本要素 / 4 个分类 / 毛重 / 产品属性 等）加上。
--
--   字段挑选原则：被取价/BOM/试算/报表 用得到的列；U9 流程字段（计划方法/超量类型/...）
--   全部不要，留 staging 表查。
--
-- 幂等：用 information_schema 检查列存在再加
-- =============================================================================

DROP PROCEDURE IF EXISTS v54_extend_master;
DELIMITER $$
CREATE PROCEDURE v54_extend_master()
BEGIN
  -- cost_element：BOM 规则关键字段（"主要材料-原材料"等）
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_material_master' AND column_name='cost_element') THEN
    ALTER TABLE lp_material_master
      ADD COLUMN cost_element VARCHAR(64) NULL COMMENT '成本要素（BOM 规则用：主要材料-原材料 等）',
      ADD KEY idx_master_cost_element (cost_element);
  END IF;

  -- 4 大业务分类
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_material_master' AND column_name='finance_category') THEN
    ALTER TABLE lp_material_master ADD COLUMN finance_category VARCHAR(64) NULL COMMENT '财务分类';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_material_master' AND column_name='purchase_category') THEN
    ALTER TABLE lp_material_master ADD COLUMN purchase_category VARCHAR(64) NULL COMMENT '采购分类';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_material_master' AND column_name='production_category') THEN
    ALTER TABLE lp_material_master ADD COLUMN production_category VARCHAR(64) NULL COMMENT '生产分类';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_material_master' AND column_name='sales_category') THEN
    ALTER TABLE lp_material_master ADD COLUMN sales_category VARCHAR(64) NULL COMMENT '销售分类';
  END IF;

  -- 主分类
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_material_master' AND column_name='main_category_code') THEN
    ALTER TABLE lp_material_master
      ADD COLUMN main_category_code VARCHAR(32) NULL COMMENT '主分类代码',
      ADD COLUMN main_category_name VARCHAR(128) NULL COMMENT '主分类名称',
      ADD KEY idx_master_main_cat (main_category_code);
  END IF;

  -- 物理属性
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_material_master' AND column_name='gross_weight_g') THEN
    ALTER TABLE lp_material_master ADD COLUMN gross_weight_g DECIMAL(18,6) NULL COMMENT '单品毛重（克）';
  END IF;

  -- 业务属性
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_material_master' AND column_name='product_property_class') THEN
    ALTER TABLE lp_material_master ADD COLUMN product_property_class VARCHAR(64) NULL COMMENT '产品属性分类（标准品/非标）';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_material_master' AND column_name='product_property') THEN
    ALTER TABLE lp_material_master ADD COLUMN product_property DECIMAL(10,6) NULL COMMENT '产品属性系数';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_material_master' AND column_name='loss_rate') THEN
    ALTER TABLE lp_material_master ADD COLUMN loss_rate DECIMAL(10,6) NULL COMMENT '净损失率';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_material_master' AND column_name='daily_capacity') THEN
    ALTER TABLE lp_material_master ADD COLUMN daily_capacity DECIMAL(18,6) NULL COMMENT '日产能';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_material_master' AND column_name='lead_time_days') THEN
    ALTER TABLE lp_material_master ADD COLUMN lead_time_days INT NULL COMMENT '加工周期（天）';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_material_master' AND column_name='package_size') THEN
    ALTER TABLE lp_material_master ADD COLUMN package_size VARCHAR(64) NULL COMMENT '包装尺寸';
  END IF;

  -- 默认值（采购/计划/供应商）
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_material_master' AND column_name='default_supplier') THEN
    ALTER TABLE lp_material_master
      ADD COLUMN default_supplier VARCHAR(128) NULL COMMENT '默认主供应商',
      ADD COLUMN default_buyer VARCHAR(64) NULL COMMENT '默认采购员',
      ADD COLUMN default_planner VARCHAR(64) NULL COMMENT '默认计划员';
  END IF;

  -- 老 U9 物料代码（反查兼容）
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_material_master' AND column_name='legacy_u9_code') THEN
    ALTER TABLE lp_material_master
      ADD COLUMN legacy_u9_code VARCHAR(64) NULL COMMENT '老U9物料代码',
      ADD KEY idx_master_legacy (legacy_u9_code);
  END IF;

  -- 元数据：导入批次（追溯哪次同步进的）
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_material_master' AND column_name='import_batch_id') THEN
    ALTER TABLE lp_material_master
      ADD COLUMN import_batch_id VARCHAR(64) NULL COMMENT '同步自 staging 的批次 ID',
      ADD KEY idx_master_batch (import_batch_id);
  END IF;
END$$
DELIMITER ;
CALL v54_extend_master();
DROP PROCEDURE v54_extend_master;
