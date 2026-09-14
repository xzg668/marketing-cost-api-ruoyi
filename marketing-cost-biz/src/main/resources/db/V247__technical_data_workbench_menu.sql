-- T4：将认证态“我的协作任务”菜单切换到新技术资料工作台。
-- 旧协作门户、旧详情路由和旧表在 T13 预切换前继续保留，避免提前破坏历史入口。
SET NAMES utf8mb4;

SET @technical_data_parent := (
  SELECT menu_id FROM sys_menu
   WHERE parent_id=0 AND path='collaboration'
   ORDER BY menu_id LIMIT 1
);

SET @technical_data_task_menu := (
  SELECT menu_id FROM sys_menu
   WHERE parent_id=@technical_data_parent AND path='tasks'
   ORDER BY menu_id LIMIT 1
);

UPDATE sys_menu
   SET menu_name='协作任务',
       remark='产品基本信息、包装、辅料和工资技术资料补录',
       update_by='system',update_time=NOW()
 WHERE menu_id=@technical_data_parent;

UPDATE sys_menu
   SET menu_name='我的协作任务',
       component='technical-data/tasks/index',
       perms='technical:data:task:list',
       remark='本人负责的报价技术资料补录任务',
       update_by='system',update_time=NOW()
 WHERE menu_id=@technical_data_task_menu;

SET @technical_data_edit_menu := (
  SELECT menu_id FROM sys_menu
   WHERE parent_id=@technical_data_task_menu
     AND perms IN ('collaboration:task:edit','technical:data:task:edit')
   ORDER BY menu_id LIMIT 1
);

UPDATE sys_menu
   SET menu_name='技术资料编辑',perms='technical:data:task:edit',
       remark='保存产品基本信息及三类技术明细',
       update_by='system',update_time=NOW()
 WHERE menu_id=@technical_data_edit_menu;

INSERT INTO sys_menu
  (menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,
   perms,icon,create_by,create_time,update_by,update_time,remark,business_unit_type)
SELECT '技术资料编辑',@technical_data_task_menu,1,'#',NULL,1,'0','F','1','0',
       'technical:data:task:edit','#','system',NOW(),'system',NOW(),
       '保存产品基本信息及三类技术明细',NULL
 WHERE @technical_data_task_menu IS NOT NULL
   AND @technical_data_edit_menu IS NULL
   AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='technical:data:task:edit');

SET @technical_data_edit_menu := (
  SELECT menu_id FROM sys_menu
   WHERE perms='technical:data:task:edit'
   ORDER BY menu_id LIMIT 1
);

-- 已经拥有旧任务菜单的角色继续拥有新列表和编辑能力；不扩大到无菜单角色。
INSERT IGNORE INTO sys_role_menu (role_id,menu_id)
SELECT existing.role_id,@technical_data_edit_menu
  FROM sys_role_menu existing
 WHERE existing.menu_id=@technical_data_task_menu
   AND @technical_data_edit_menu IS NOT NULL;
