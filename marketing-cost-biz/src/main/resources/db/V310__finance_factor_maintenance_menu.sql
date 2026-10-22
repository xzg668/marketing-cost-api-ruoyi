-- PRC-02：复用原财务Cu页面作为统一财务基价维护入口。
-- 只给已能查看/导入公共基价的角色补入口和Cu查询权限，不扩大编辑权限。
UPDATE sys_menu SET menu_name = '财务基价', update_time = NOW()
WHERE component = 'pages:FinanceCuBasePricePage';

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT granted.role_id, entry.menu_id
FROM sys_role_menu granted
JOIN sys_menu capability ON capability.menu_id = granted.menu_id
JOIN sys_menu entry ON entry.component = 'pages:FinanceCuBasePricePage'
WHERE capability.perms IN ('price:finance-base:list', 'price:finance-base:import')
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu existing
                  WHERE existing.role_id = granted.role_id AND existing.menu_id = entry.menu_id);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT granted.role_id, permission.menu_id
FROM sys_role_menu granted
JOIN sys_menu capability ON capability.menu_id = granted.menu_id
JOIN sys_menu permission ON permission.perms = 'cost:finance-cu-base:query'
WHERE capability.perms IN ('price:finance-base:list', 'price:finance-base:import')
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu existing
                  WHERE existing.role_id = granted.role_id AND existing.menu_id = permission.menu_id);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT granted.role_id, parent.menu_id
FROM sys_role_menu granted
JOIN sys_menu entry ON entry.menu_id = granted.menu_id
JOIN sys_menu parent ON parent.menu_id = entry.parent_id
WHERE entry.component = 'pages:FinanceCuBasePricePage'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu existing
                  WHERE existing.role_id = granted.role_id AND existing.menu_id = parent.menu_id);
