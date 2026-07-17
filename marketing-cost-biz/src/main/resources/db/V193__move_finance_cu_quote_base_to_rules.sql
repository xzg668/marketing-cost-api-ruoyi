-- =====================================================================
-- V193: 财务 Cu 报价基准菜单归入“规则配置”
-- =====================================================================

SET NAMES utf8mb4;

START TRANSACTION;

UPDATE sys_menu
SET parent_id = 40475,
    order_num = 50,
    path = 'finance-cu-base',
    component = 'pages:FinanceCuBasePricePage',
    menu_type = 'C',
    visible = '0',
    status = '0',
    perms = 'cost:finance-cu-base:query',
    update_by = 'V193',
    update_time = NOW(),
    remark = '规则配置：按当前业务单元维护财务报价Cu月度基准，页面口径为元/吨'
WHERE menu_id = 40477;

-- 已拥有财务 Cu 页面入口的角色自动获得“规则配置”父目录。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role_id, 40475
FROM sys_role_menu
WHERE menu_id = 40477;

COMMIT;
