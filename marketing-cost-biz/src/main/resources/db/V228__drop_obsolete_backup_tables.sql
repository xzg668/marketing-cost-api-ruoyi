-- =============================================================================
-- V228: 删除开发期一次性备份表
--
-- 这些 bak_* / copy1 表不属于运行时业务模型，且经核对没有应用、外键、视图、触发器、
-- 存储过程或事件引用。EasyData/U9/CMS 导入仍可能使用的 tmp_* 暂存表明确保留。
-- =============================================================================

SET NAMES utf8mb4;

DROP TABLE IF EXISTS `bak_20260707_signal_test_cms_plan_cost_raw`;
DROP TABLE IF EXISTS `bak_20260707_signal_test_cms_product_subject_cost_raw`;
DROP TABLE IF EXISTS `bak_20260707_signal_test_cms_subject_setting_raw`;
DROP TABLE IF EXISTS `bak_20260707_signal_test_cms_workshop_labor_raw`;
DROP TABLE IF EXISTS `bak_20260707_signal_test_lp_material_scrap_ref`;
DROP TABLE IF EXISTS `bak_lp_bom_raw_hierarchy_t9_20260708`;
DROP TABLE IF EXISTS `bak_lp_bom_u9_source_t9_20260708`;
DROP TABLE IF EXISTS `bak_lp_material_master_raw_t9_20260708`;
DROP TABLE IF EXISTS `bak_lp_u9_bom_byproduct_master_t9_20260708`;
DROP TABLE IF EXISTS `bak_tmp_lp_material_master_raw_t8_20260708`;
DROP TABLE IF EXISTS `lp_material_master_raw_copy1`;
