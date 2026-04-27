-- =====================================================================
-- V25: 联动价改造 —— finance_base_price 原价列 + Excel 导入批次 + scrap_ref 表
--   price_original  影响因素 10 的"价格-原价"列（上期对比值）
--   import_batch_id Excel 导入批次 UUID（供批次审计/回滚）
--   lp_material_scrap_ref  部品→废料映射（供 scrap_price_incl 派生）
-- =====================================================================

-- 1) 财务基价加 2 列
ALTER TABLE `lp_finance_base_price`
  ADD COLUMN `price_original` DECIMAL(20,8) DEFAULT NULL
    COMMENT '上期原价（影响因素10 "价格-原价" 列）',
  ADD COLUMN `import_batch_id` VARCHAR(64) DEFAULT NULL
    COMMENT 'Excel 导入批次 ID（UUID）';

ALTER TABLE `lp_finance_base_price`
  ADD KEY `idx_batch` (`import_batch_id`);

-- 2) 部品-废料映射表（供 scrap_price_incl 派生变量按 material_code 反查）
CREATE TABLE IF NOT EXISTS `lp_material_scrap_ref` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `material_code` VARCHAR(64) NOT NULL COMMENT '部品或原材料代码',
  `scrap_code` VARCHAR(64) NOT NULL COMMENT '对应废料代码（CMS 体系）',
  `ratio` DECIMAL(10,6) DEFAULT 1.0 COMMENT '抵减比例（如铜沫 0.92）',
  `effective_from` DATE DEFAULT NULL COMMENT '生效日期',
  `effective_to` DATE DEFAULT NULL COMMENT '失效日期',
  `business_unit_type` VARCHAR(16) NOT NULL DEFAULT 'COMMERCIAL' COMMENT '业务单元隔离',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_material_scrap` (`material_code`, `scrap_code`, `business_unit_type`),
  KEY `idx_scrap_material` (`material_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='部品-废料映射（联动价 scrap_price_incl 派生用）';
