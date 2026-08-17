-- QCBP-19：只新增财务协作菜单和权限数据，不修改现有业务表结构。
SET NAMES utf8mb4;

-- 财务审核是明确责任角色；管理员通配权限不能替代指定财务身份。
-- 这里只创建角色和菜单授权，不创建用户，也不自动给现有账号分配角色。
INSERT IGNORE INTO sys_role
  (role_id,role_name,role_key,role_sort,data_scope,status,del_flag,create_by,create_time,
   update_by,update_time,remark)
VALUES
  (13,'补录财务审核员','finance_reviewer',5,'2','0','0','system',NOW(),'system',NOW(),
   '只审核本人被指定的BOM、包装和价格补录任务');

SET @collaboration_parent := (
  SELECT menu_id FROM sys_menu WHERE parent_id=0 AND path='collaboration' ORDER BY menu_id LIMIT 1
);

INSERT INTO sys_menu
  (menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,
   perms,icon,create_by,create_time,update_by,update_time,remark,business_unit_type)
SELECT '补录审核',@collaboration_parent,2,'reviews','collaboration/finance/index',1,'0','C','0','0',
       'collaboration:review:read','audit','system',NOW(),'system',NOW(),
       '指定财务人员审核技术补录内容',NULL
WHERE @collaboration_parent IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE component='collaboration/finance/index');

SET @finance_review_menu := (
  SELECT menu_id FROM sys_menu WHERE component='collaboration/finance/index' ORDER BY menu_id LIMIT 1
);

INSERT INTO sys_menu
  (menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,
   perms,icon,create_by,create_time,update_by,update_time,remark,business_unit_type)
SELECT '补录审核处理',@finance_review_menu,1,'#',NULL,1,'0','F','1','0',
       'collaboration:review:decide','#','system',NOW(),'system',NOW(),
       '逐项审核、退回及统一生效',NULL
WHERE @finance_review_menu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='collaboration:review:decide');

SET @finance_review_decide := (
  SELECT menu_id FROM sys_menu WHERE perms='collaboration:review:decide' ORDER BY menu_id LIMIT 1
);

-- 该角色只获得协作父菜单、本人审核清单和审核动作；组织、责任人仍由服务端二次校验。
INSERT IGNORE INTO sys_role_menu (role_id,menu_id)
SELECT role.role_id, menu.menu_id
FROM sys_role role
JOIN sys_menu menu ON menu.menu_id IN (
  @collaboration_parent,
  @finance_review_menu,
  @finance_review_decide
)
WHERE LOWER(role.role_key)='finance_reviewer'
  AND role.status='0' AND role.del_flag='0';
