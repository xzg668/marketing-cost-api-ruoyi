-- 废料价格对全部报价员、业务单元总监和管理员开放。
-- 只补已有菜单授权；不修改用户角色、其他菜单或业务数据。可重复执行。
START TRANSACTION;

INSERT INTO sys_role_menu(role_id, menu_id)
SELECT role.role_id, menu.menu_id
FROM sys_role role
JOIN sys_menu menu ON menu.status = '0'
  AND (menu.perms IN ('price:scrap:list', 'price:scrap:import', 'price:scrap:edit')
       OR menu.menu_id IN (SELECT entry.parent_id FROM sys_menu entry
                          WHERE entry.perms = 'price:scrap:list'))
WHERE role.role_key IN ('admin', 'bu_director', 'bu_staff')
  AND role.status = '0' AND role.del_flag = '0'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu existing
                  WHERE existing.role_id = role.role_id AND existing.menu_id = menu.menu_id);

COMMIT;
