-- V136: 去掉“价格准备”菜单前的 Operation 图标
--
-- 该图标是三条滑杆样式，和价格准备业务含义不匹配，侧边栏视觉上也偏突兀。
-- 只清空菜单图标，不影响菜单权限、路由和页面功能。

SET NAMES utf8mb4;

UPDATE sys_menu
SET icon = NULL,
    update_by = 'system',
    update_time = NOW()
WHERE menu_id = 40454
   OR (menu_name = '价格准备' AND path = '/cost/price-prepare');
