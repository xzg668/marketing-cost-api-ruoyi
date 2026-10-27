-- MPP-04: menu/permission data only; not part of structure-only synchronization.
-- Runtime routes are granted by sys_menu; no static frontend menu fallback.
START TRANSACTION;
INSERT INTO sys_menu
  (menu_name,parent_id,order_num,path,component,menu_type,visible,status,perms,icon,create_by,create_time,business_unit_type)
SELECT '家用部件询价',parent.menu_id,70,'/price/household-inquiry','pages:HouseholdInquiryPage',
       'C','0','0','price:household-inquiry:list','table','system',NOW(),NULL
FROM sys_menu parent
WHERE parent.parent_id=0 AND parent.path='price' AND parent.menu_type='M'
  AND NOT EXISTS (SELECT 1 FROM sys_menu existing WHERE existing.perms='price:household-inquiry:list');

INSERT INTO sys_menu
  (menu_name,parent_id,order_num,menu_type,visible,status,perms,create_by,create_time,business_unit_type)
SELECT action.label,entry.menu_id,action.sort,'F','0','0',action.permission,'system',NOW(),NULL
FROM (SELECT '询价版本查询' label,'price:household-inquiry:query' permission,1 sort
  UNION ALL SELECT '询价原件预览及发布','price:household-inquiry:import',2
  UNION ALL SELECT '询价财务参考及原件','price:household-inquiry:download',3) action
JOIN sys_menu entry ON entry.perms='price:household-inquiry:list'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu existing WHERE existing.perms=action.permission);

INSERT INTO sys_role_menu(role_id,menu_id)
SELECT role.role_id,menu.menu_id FROM sys_role role JOIN sys_menu menu
  ON menu.perms IN ('price:household-inquiry:list','price:household-inquiry:query',
                   'price:household-inquiry:import','price:household-inquiry:download')
-- 报价处长=bu_director，报价员=bu_staff；不向技术协作者或补录财务审核员默认开放。
WHERE role.role_key IN ('admin','bu_director','bu_staff') AND role.status='0' AND role.del_flag='0'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu existing WHERE existing.role_id=role.role_id AND existing.menu_id=menu.menu_id);
COMMIT;
