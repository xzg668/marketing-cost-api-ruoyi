-- QCBP-23：在同一协作父菜单下提供BOM与补价两个清晰入口；两者仍使用同一产品任务。
SET NAMES utf8mb4;

SET @collaboration_parent := (
  SELECT menu_id FROM sys_menu WHERE parent_id=0 AND path='collaboration' ORDER BY menu_id LIMIT 1
);

UPDATE sys_menu
SET menu_name='BOM技术协作', order_num=1, update_by='system', update_time=NOW(),
    remark='本人BOM、裸品包装及其连续补价任务'
WHERE parent_id=@collaboration_parent
  AND component='collaboration/technical/index'
  AND path='tasks';

INSERT INTO sys_menu
  (menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,
   perms,icon,create_by,create_time,update_by,update_time,remark,business_unit_type)
SELECT '补价协作',@collaboration_parent,2,'prices','collaboration/technical/index',1,'0','C','0','0',
       'collaboration:task:read','money','system',NOW(),'system',NOW(),
       '本人底层物料缺价任务；与BOM协作共用产品任务和状态机',NULL
WHERE @collaboration_parent IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM sys_menu WHERE parent_id=@collaboration_parent AND path='prices'
  );

-- 统一财务审核的实际菜单地址与前端深链地址。
UPDATE sys_menu
SET path='finance-reviews', order_num=3, update_by='system', update_time=NOW()
WHERE parent_id=@collaboration_parent AND component='collaboration/finance/index';

SET @price_task_menu := (
  SELECT menu_id FROM sys_menu WHERE parent_id=@collaboration_parent AND path='prices'
  ORDER BY menu_id LIMIT 1
);

INSERT IGNORE INTO sys_role_menu (role_id,menu_id)
SELECT role.role_id,@price_task_menu
FROM sys_role role
WHERE LOWER(role.role_key)='oa_collaborator' AND role.status='0' AND role.del_flag='0'
  AND @price_task_menu IS NOT NULL;
