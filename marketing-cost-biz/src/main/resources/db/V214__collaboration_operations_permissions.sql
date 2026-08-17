-- QCBP-25：协作运维只开放显式权限；不向普通技术或财务角色自动授权。
SET NAMES utf8mb4;

UPDATE sys_menu
SET menu_name='报价协作任务发起',update_by='system',update_time=NOW()
WHERE perms='collaboration:task:create';

SET @collaboration_parent := (
  SELECT menu_id FROM sys_menu WHERE parent_id=0 AND path='collaboration' ORDER BY menu_id LIMIT 1
);

INSERT INTO sys_menu
  (menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,
   perms,icon,create_by,create_time,update_by,update_time,remark,business_unit_type)
SELECT '协作运维查询',@collaboration_parent,90,'#',NULL,1,'0','F','1','0',
       'collaboration:operations:read','#','system',NOW(),'system',NOW(),
       '对账、Outbox和发布失败查询；默认仅管理员通配权限可用',NULL
WHERE @collaboration_parent IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='collaboration:operations:read');

INSERT INTO sys_menu
  (menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,
   perms,icon,create_by,create_time,update_by,update_time,remark,business_unit_type)
SELECT '协作人工补偿',@collaboration_parent,91,'#',NULL,1,'0','F','1','0',
       'collaboration:operations:compensate','#','system',NOW(),'system',NOW(),
       '需原因和幂等键的受控人工补偿；默认仅管理员通配权限可用',NULL
WHERE @collaboration_parent IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='collaboration:operations:compensate');
