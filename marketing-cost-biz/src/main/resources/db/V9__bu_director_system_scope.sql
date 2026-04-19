-- 收紧 BU_DIRECTOR 的系统管理边界：
-- 仅保留用户管理入口；角色、部门、岗位管理页由 admin 维护。
-- 用户管理表单所需的角色/部门/岗位只读数据由独立接口放行。
DELETE srm
FROM sys_role_menu srm
JOIN sys_menu m ON m.menu_id = srm.menu_id
WHERE srm.role_id = 10
  AND (
      m.menu_id IN (102, 104, 105)
      OR m.perms LIKE 'system:role:%'
      OR m.perms LIKE 'system:dept:%'
      OR m.perms LIKE 'system:post:%'
  );
