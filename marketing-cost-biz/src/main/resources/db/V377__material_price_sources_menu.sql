START TRANSACTION;
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,menu_type,visible,status,perms,icon,create_by,create_time,business_unit_type)
SELECT '物料价格来源汇总',parent.menu_id,74,'/price/material-sources','pages:MaterialPriceSourcesPage','C','0','0','price:material-sources:list','table','system',NOW(),NULL
FROM sys_menu parent WHERE parent.parent_id=0 AND parent.path IN ('price','/price') AND parent.menu_type='M'
  AND NOT EXISTS (SELECT 1 FROM sys_menu existing WHERE existing.perms='price:material-sources:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,menu_type,visible,status,perms,create_by,create_time,business_unit_type)
SELECT '料号属性维护',entry.menu_id,1,'F','0','0','price:material-sources:edit','system',NOW(),NULL
FROM sys_menu entry WHERE entry.perms='price:material-sources:list'
  AND NOT EXISTS (SELECT 1 FROM sys_menu existing WHERE existing.perms='price:material-sources:edit');
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT role.role_id,menu.menu_id FROM sys_role role JOIN sys_menu menu
  ON menu.perms IN ('price:material-sources:list','price:material-sources:edit')
    OR menu.menu_id IN (SELECT parent_id FROM sys_menu WHERE perms='price:material-sources:list')
WHERE role.role_key IN ('admin','bu_director','bu_staff') AND role.status='0' AND role.del_flag='0'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu existing WHERE existing.role_id=role.role_id AND existing.menu_id=menu.menu_id);
COMMIT;
