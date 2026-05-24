-- =============================================================================
-- V118: 净损失率配置旧必填字段默认值
-- -----------------------------------------------------------------------------
-- 新的报价净损失率配置按 年度 + 产品料号/产品型号 匹配。
-- business_unit / product_category / product_subcategory 是旧表必填字段，
-- 新导入模板不再保证全部维护，统一给默认空字符串避免插入失败。
-- =============================================================================

SET NAMES utf8mb4;

ALTER TABLE lp_quality_loss_rate
  MODIFY business_unit VARCHAR(80) NOT NULL DEFAULT '' COMMENT '历史事业部字段，新口径以 business_division 展示',
  MODIFY product_category VARCHAR(80) NOT NULL DEFAULT '' COMMENT '产品大类，展示用，不参与净损失率匹配',
  MODIFY product_subcategory VARCHAR(80) NOT NULL DEFAULT '' COMMENT '历史产品小类字段，新口径不参与匹配';
