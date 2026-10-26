-- MPP-11: menu/permission data; apply separately from structure-only synchronization.
START TRANSACTION;
INSERT INTO sys_menu
  (menu_name,parent_id,order_num,path,component,menu_type,visible,status,perms,icon,create_by,create_time,business_unit_type)
SELECT '月度物料核算价',parent.menu_id,71,'/price/monthly-material','pages:MonthlyMaterialPricePage',
       'C','0','0','price:monthly-material:list','table','system',NOW(),NULL
FROM sys_menu parent
WHERE parent.parent_id=0 AND parent.path='price' AND parent.menu_type='M'
  AND NOT EXISTS (SELECT 1 FROM sys_menu existing WHERE existing.perms='price:monthly-material:list');
INSERT INTO sys_menu
  (menu_name,parent_id,order_num,menu_type,visible,status,perms,create_by,create_time,business_unit_type)
SELECT action.label,entry.menu_id,action.sort,'F','0','0',action.permission,'system',NOW(),NULL
FROM (SELECT '月价采用依据' label,'price:monthly-material:query' permission,1 sort
  UNION ALL SELECT '月价核算引用','price:monthly-material:references',2) action
JOIN sys_menu entry ON entry.perms='price:monthly-material:list'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu existing WHERE existing.perms=action.permission);
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT role.role_id,menu.menu_id FROM sys_role role JOIN sys_menu menu
  ON menu.perms IN ('price:monthly-material:list','price:monthly-material:query','price:monthly-material:references')
WHERE role.role_key='admin'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu existing WHERE existing.role_id=role.role_id AND existing.menu_id=menu.menu_id);
COMMIT;
