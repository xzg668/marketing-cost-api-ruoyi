-- =============================================================================
-- V258: 修复技术资料菜单在旧协作菜单已提前清理时无法原位切换的问题
-- -----------------------------------------------------------------------------
-- V247/V252 优先复用旧子菜单；部分开发库曾先执行 V256，旧子菜单已不存在，
-- 导致只剩“协作任务”父目录。本迁移只做幂等的菜单与角色授权重建。
-- =============================================================================

SET NAMES utf8mb4;

SET @technical_data_parent := (
  SELECT menu_id FROM sys_menu
   WHERE parent_id=0 AND path='collaboration'
   ORDER BY menu_id LIMIT 1
);

INSERT INTO sys_menu
  (menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,
   perms,icon,create_by,create_time,update_by,update_time,remark,business_unit_type)
SELECT '协作任务',0,6,'collaboration','Layout',1,'0','M','0','0',
       NULL,'Connection','system',NOW(),'system',NOW(),
       '产品基本信息、包装、辅料和工资技术资料补录',NULL
 WHERE @technical_data_parent IS NULL;

SET @technical_data_parent := (
  SELECT menu_id FROM sys_menu
   WHERE parent_id=0 AND path='collaboration'
   ORDER BY menu_id LIMIT 1
);

UPDATE sys_menu
   SET menu_name='协作任务',visible='0',status='0',
       remark='产品基本信息、包装、辅料和工资技术资料补录',
       update_by='system',update_time=NOW()
 WHERE menu_id=@technical_data_parent;

INSERT INTO sys_menu
  (menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,
   perms,icon,create_by,create_time,update_by,update_time,remark,business_unit_type)
SELECT '我的协作任务',@technical_data_parent,1,'tasks','technical-data/tasks/index',
       1,'0','C','0','0','technical:data:task:list','list',
       'system',NOW(),'system',NOW(),'本人负责的报价技术资料补录任务',NULL
 WHERE @technical_data_parent IS NOT NULL
   AND NOT EXISTS (
     SELECT 1 FROM sys_menu
      WHERE parent_id=@technical_data_parent
        AND (path='tasks' OR perms='technical:data:task:list')
   );

SET @technical_data_task_menu := (
  SELECT menu_id FROM sys_menu
   WHERE parent_id=@technical_data_parent
     AND (path='tasks' OR perms='technical:data:task:list')
   ORDER BY menu_id LIMIT 1
);

UPDATE sys_menu
   SET menu_name='我的协作任务',order_num=1,path='tasks',
       component='technical-data/tasks/index',menu_type='C',visible='0',status='0',
       perms='technical:data:task:list',icon='list',
       remark='本人负责的报价技术资料补录任务',
       update_by='system',update_time=NOW()
 WHERE menu_id=@technical_data_task_menu;

INSERT INTO sys_menu
  (menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,
   perms,icon,create_by,create_time,update_by,update_time,remark,business_unit_type)
SELECT '补录审核',@technical_data_parent,2,'finance-reviews','technical-data/reviews/index',
       1,'0','C','0','0','technical:data:review:list','audit',
       'system',NOW(),'system',NOW(),'按产品模块审核新技术资料不可变提交版本',NULL
 WHERE @technical_data_parent IS NOT NULL
   AND NOT EXISTS (
     SELECT 1 FROM sys_menu
      WHERE parent_id=@technical_data_parent
        AND (path='finance-reviews' OR perms='technical:data:review:list')
   );

SET @technical_data_review_menu := (
  SELECT menu_id FROM sys_menu
   WHERE parent_id=@technical_data_parent
     AND (path='finance-reviews' OR perms='technical:data:review:list')
   ORDER BY menu_id LIMIT 1
);

UPDATE sys_menu
   SET menu_name='补录审核',order_num=2,path='finance-reviews',
       component='technical-data/reviews/index',menu_type='C',visible='0',status='0',
       perms='technical:data:review:list',icon='audit',
       remark='按产品模块审核新技术资料不可变提交版本',
       update_by='system',update_time=NOW()
 WHERE menu_id=@technical_data_review_menu;

INSERT INTO sys_menu
  (menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,
   perms,icon,create_by,create_time,update_by,update_time,remark,business_unit_type)
SELECT '技术资料编辑',@technical_data_task_menu,1,'#',NULL,1,'0','F','1','0',
       'technical:data:task:edit','#','system',NOW(),'system',NOW(),
       '保存产品基本信息及三类技术明细',NULL
 WHERE @technical_data_task_menu IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='technical:data:task:edit');

INSERT INTO sys_menu
  (menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,
   perms,icon,create_by,create_time,update_by,update_time,remark,business_unit_type)
SELECT '技术资料任务管理',@technical_data_task_menu,2,'#',NULL,1,'0','F','1','0',
       'technical:data:admin:operate','#','system',NOW(),'system',NOW(),
       '改派、受控代录、解锁、OA重试、作废和短票签发',NULL
 WHERE @technical_data_task_menu IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='technical:data:admin:operate');

INSERT INTO sys_menu
  (menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,
   perms,icon,create_by,create_time,update_by,update_time,remark,business_unit_type)
SELECT '技术资料审核处理',@technical_data_review_menu,1,'#',NULL,1,'0','F','1','0',
       'technical:data:review:decide','#','system',NOW(),'system',NOW(),
       '通过或退回技术资料产品模块',NULL
 WHERE @technical_data_review_menu IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='technical:data:review:decide');

SET @technical_data_edit_menu := (
  SELECT menu_id FROM sys_menu WHERE perms='technical:data:task:edit'
  ORDER BY menu_id LIMIT 1
);
SET @technical_data_admin_menu := (
  SELECT menu_id FROM sys_menu WHERE perms='technical:data:admin:operate'
  ORDER BY menu_id LIMIT 1
);
SET @technical_data_review_decide_menu := (
  SELECT menu_id FROM sys_menu WHERE perms='technical:data:review:decide'
  ORDER BY menu_id LIMIT 1
);

-- 技术协作者：本人任务列表与录入；超级管理员显式保留管理授权。
INSERT IGNORE INTO sys_role_menu (role_id,menu_id)
SELECT role.role_id,menu.menu_id
  FROM sys_role role
  JOIN sys_menu menu ON menu.menu_id IN (
    @technical_data_parent,@technical_data_task_menu,@technical_data_edit_menu)
 WHERE LOWER(role.role_key) IN ('technical_collaborator','admin')
   AND role.status='0' AND role.del_flag='0';

INSERT IGNORE INTO sys_role_menu (role_id,menu_id)
SELECT role.role_id,@technical_data_admin_menu
  FROM sys_role role
 WHERE LOWER(role.role_key)='admin'
   AND role.status='0' AND role.del_flag='0'
   AND @technical_data_admin_menu IS NOT NULL;

-- 报价发起人、事业部负责人和现有指定审核角色：审核列表与审核动作。
INSERT IGNORE INTO sys_role_menu (role_id,menu_id)
SELECT role.role_id,menu.menu_id
  FROM sys_role role
  JOIN sys_menu menu ON menu.menu_id IN (
    @technical_data_parent,@technical_data_review_menu,@technical_data_review_decide_menu)
 WHERE LOWER(role.role_key) IN ('bu_staff','bu_director','finance_reviewer','admin')
   AND role.status='0' AND role.del_flag='0';
