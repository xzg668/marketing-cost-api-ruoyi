START TRANSACTION;
INSERT INTO sys_menu(menu_name,parent_id,order_num,menu_type,visible,status,perms,create_by,create_time,business_unit_type)
SELECT '更新物料价格来源',entry.menu_id,2,'F','0','0','price:material-sources:refresh','system',NOW(),NULL
FROM sys_menu entry WHERE entry.perms='price:material-sources:list'
  AND NOT EXISTS (SELECT 1 FROM sys_menu existing WHERE existing.perms='price:material-sources:refresh');
-- 报价员继续查看；财务确认属性及更新清单。
DELETE rm FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id=rm.menu_id JOIN sys_role r ON r.role_id=rm.role_id
WHERE m.perms='price:material-sources:edit' AND r.role_key='bu_staff';
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT r.role_id,m.menu_id FROM sys_role r JOIN sys_menu m
  ON m.perms IN ('price:material-sources:list','price:material-sources:edit','price:material-sources:refresh')
    OR m.menu_id IN (SELECT parent_id FROM sys_menu WHERE perms='price:material-sources:list')
WHERE r.role_key IN ('admin','bu_director','finance_reviewer') AND r.status='0' AND r.del_flag='0'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu old WHERE old.role_id=r.role_id AND old.menu_id=m.menu_id);
COMMIT;
