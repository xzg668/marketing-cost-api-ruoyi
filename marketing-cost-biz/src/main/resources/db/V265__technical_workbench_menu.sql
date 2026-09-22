-- TW-06：原位更名，沿用任务菜单及其角色权限，不增加同义入口。
SET NAMES utf8mb4;
UPDATE sys_menu child
JOIN sys_menu parent ON parent.menu_id=child.parent_id
SET child.menu_name='补录工作台',child.component='technical-data/tasks/index',
    child.perms='technical:data:task:list',child.update_by='system',child.update_time=NOW(),
    child.remark='查看本人负责模块；有管理权限者可直接办理其管理范围内任务'
WHERE parent.parent_id=0 AND parent.path='collaboration' AND child.path='tasks';

-- 已有编辑或管理权限的角色能从站内进入；不向无补录权限的角色授予编辑权。
INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
SELECT grants.role_id,workbench.menu_id
FROM sys_role_menu grants JOIN sys_menu permission ON permission.menu_id=grants.menu_id
JOIN sys_menu workbench ON workbench.perms='technical:data:task:list' AND workbench.path='tasks'
WHERE permission.perms IN ('technical:data:task:edit','technical:data:admin:operate');
