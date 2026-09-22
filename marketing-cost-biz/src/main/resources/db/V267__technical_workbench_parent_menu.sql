-- 动态菜单要求父目录也在角色菜单集合中；仅补齐已有工作台角色的入口目录。
SET NAMES utf8mb4;
INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
SELECT grants.role_id,parent.menu_id
FROM sys_role_menu grants
JOIN sys_menu workbench ON workbench.menu_id=grants.menu_id
JOIN sys_menu parent ON parent.menu_id=workbench.parent_id
WHERE workbench.perms='technical:data:task:list' AND workbench.path='tasks'
  AND parent.parent_id=0 AND parent.path='collaboration';
