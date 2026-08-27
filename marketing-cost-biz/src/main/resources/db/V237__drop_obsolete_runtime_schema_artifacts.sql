-- =============================================================================
-- V237: 清理正式 schema 中不属于运行时模型的历史遗留表
--
-- 只删除已经完成引用链核对的七张表：
--   1. _moji_backup_20260429：一次性乱码修复备份，完整库备份中仍可追溯；
--   2. lp_material_price_type_bak_20260518_excel：一次性 Excel 导入备份；
--   3. bom_stop_drill_rule：已由 lp_bom_settlement_rule 替代，V148 已下线，
--      此处幂等兜底清理由未完整执行历史迁移造成的 schema 漂移。
--   4. lp_bom_manage_item：BOM 管理已直接查询 lp_bom_costing_row；
--   5. lp_make_part_spec / lp_price_scrap：旧制造件规格及旧废料价兼容链已下线，
--      当前制造件生成使用 BOM 子树、净重、物料废料映射和正式价格准备结果；
--   6. lp_raw_material_breakdown：从未接入运行链的 Task #8 骨架表。
--
-- EasyData/U9/CMS 的 tmp_* 暂存表仍承担外部数据落地职责，不在本次清理范围。
-- =============================================================================

SET NAMES utf8mb4;

DROP TABLE IF EXISTS `_moji_backup_20260429`;
DROP TABLE IF EXISTS `lp_material_price_type_bak_20260518_excel`;
DROP TABLE IF EXISTS `bom_stop_drill_rule`;
DROP TABLE IF EXISTS `lp_bom_manage_item`;
DROP TABLE IF EXISTS `lp_make_part_spec`;
DROP TABLE IF EXISTS `lp_price_scrap`;
DROP TABLE IF EXISTS `lp_raw_material_breakdown`;
