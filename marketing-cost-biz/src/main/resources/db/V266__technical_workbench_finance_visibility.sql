-- 管理员和报价员查看补录资料；查看权限不生成 OA 待办，也不授予技术编辑权限。
SET NAMES utf8mb4;
UPDATE sys_menu child
JOIN sys_menu parent ON parent.menu_id=child.parent_id
SET child.remark='技术员办理本人分派；管理员和报价员查看补录资料',
    child.update_by='system',child.update_time=NOW()
WHERE parent.parent_id=0 AND parent.path='collaboration' AND child.path='tasks';

INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
SELECT grants.role_id,workbench.menu_id
FROM sys_role_menu grants JOIN sys_menu permission ON permission.menu_id=grants.menu_id
JOIN sys_menu workbench ON workbench.perms='technical:data:task:list' AND workbench.path='tasks'
WHERE permission.perms='ingest:quote:cost-run:execute';
