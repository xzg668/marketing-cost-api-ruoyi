-- =============================================================================
-- V111: 修复包装组件价格菜单层级和图标
-- -----------------------------------------------------------------------------
-- 包装组件价格是“价格源管理”的平级业务页面，不是“制造件价格生成”的下级菜单。
-- 早期环境如果已执行过旧 seed，Flyway 不会重跑 V105，因此用独立修复脚本校正现有库。
-- =============================================================================

SET NAMES utf8mb4;

UPDATE sys_menu package_menu
JOIN (
  SELECT menu_id
  FROM sys_menu
  WHERE (path IN ('price', '/price') OR menu_name = '价格源管理')
    AND menu_type = 'M'
  ORDER BY CASE WHEN parent_id = 0 THEN 0 ELSE 1 END,
           CASE WHEN path IN ('price', '/price') THEN 0 ELSE 1 END,
           menu_id
  LIMIT 1
) price_root
SET package_menu.parent_id = price_root.menu_id,
    package_menu.order_num = 5,
    package_menu.icon = '#',
    package_menu.update_time = NOW(),
    package_menu.remark = '包装组件价格：价格源管理下的包装组件月度结构、价格和缺口清单'
WHERE package_menu.menu_type = 'C'
  AND (
    package_menu.menu_id = 40449
    OR package_menu.path = '/price/package-component'
    OR package_menu.component = 'price/package-component/index'
    OR package_menu.perms = 'price:package-component:list'
  );

UPDATE sys_menu
SET parent_id = 40449,
    icon = '#',
    update_time = NOW()
WHERE perms LIKE 'price:package-component:%'
  AND menu_type = 'F';

