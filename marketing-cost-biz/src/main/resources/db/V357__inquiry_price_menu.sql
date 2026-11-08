-- Menu data is separate from structure synchronization.
START TRANSACTION;
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,menu_type,visible,status,perms,icon,create_by,create_time,business_unit_type)
SELECT '询价',parent.menu_id,73,'/price/inquiry','pages:InquiryPricePage','C','0','0','price:inquiry:list','table','system',NOW(),NULL
FROM sys_menu parent WHERE parent.parent_id=0 AND parent.path IN ('price','/price') AND parent.menu_type='M'
  AND NOT EXISTS (SELECT 1 FROM sys_menu existing WHERE existing.perms='price:inquiry:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,menu_type,visible,status,perms,create_by,create_time,business_unit_type)
SELECT action.label,entry.menu_id,action.sort,'F','0','0',action.permission,'system',NOW(),NULL
FROM (SELECT '询价历史查询' label,'price:inquiry:query' permission,1 sort
  UNION ALL SELECT '询价导入','price:inquiry:import',2
  UNION ALL SELECT '询价原件下载','price:inquiry:download',3) action
JOIN sys_menu entry ON entry.perms='price:inquiry:list'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu existing WHERE existing.perms=action.permission);
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT role.role_id,menu.menu_id FROM sys_role role JOIN sys_menu menu
  ON menu.perms IN ('price:inquiry:list','price:inquiry:query','price:inquiry:import','price:inquiry:download')
WHERE role.role_key IN ('admin','bu_director','bu_staff') AND role.status='0' AND role.del_flag='0'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu existing WHERE existing.role_id=role.role_id AND existing.menu_id=menu.menu_id);
COMMIT;
