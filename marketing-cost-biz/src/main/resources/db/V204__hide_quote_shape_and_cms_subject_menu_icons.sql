-- =============================================================================
-- V204: 清除规则配置下两个菜单的前置图标
-- -----------------------------------------------------------------------------
-- 仅调整 sys_menu 展示字段，不修改权限、规则、BOM、价格或历史报价数据。
-- =============================================================================

SET NAMES utf8mb4;

UPDATE sys_menu
SET icon = '#', update_by = 'admin', update_time = NOW()
WHERE menu_id = 40483
  AND menu_name = '报价 BOM 物料形态规则';

UPDATE sys_menu
SET icon = '#', update_by = 'admin', update_time = NOW()
WHERE menu_id = 40239
  AND menu_name = 'CMS 科目设置';
