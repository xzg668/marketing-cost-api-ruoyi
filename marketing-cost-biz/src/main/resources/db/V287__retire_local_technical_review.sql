-- 本地逐模块审核已经由 OA 人员提交/领导事件链替代。
-- 有旧审核数据或额外数据库引用时中止升级，先迁移历史与待办，不将旧通过项当成新审批。
SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS assert_retired_technical_review_ready;
DELIMITER $$
CREATE PROCEDURE assert_retired_technical_review_ready()
BEGIN
  DECLARE old_rows BIGINT DEFAULT 0;
  DECLARE external_refs BIGINT DEFAULT 0;
  DECLARE stored_refs BIGINT DEFAULT 0;
  DECLARE unexpected_children BIGINT DEFAULT 0;
  DECLARE duplicate_permissions BIGINT DEFAULT 0;

  SELECT COUNT(*) INTO old_rows FROM lp_quote_tech_review_item;
  IF old_rows <> 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='TW20_BLOCKED: 旧逐模块审核项仍有数据';
  END IF;

  SELECT COUNT(*) INTO external_refs
    FROM information_schema.key_column_usage
   WHERE referenced_table_schema=DATABASE()
     AND referenced_table_name='lp_quote_tech_review_item'
     AND table_name<>'lp_quote_tech_review_item';
  IF external_refs <> 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='TW20_BLOCKED: 其他表仍引用旧审核项';
  END IF;

  SELECT (SELECT COUNT(*) FROM information_schema.views
           WHERE table_schema=DATABASE() AND LOWER(view_definition) LIKE '%lp_quote_tech_review_item%')
       + (SELECT COUNT(*) FROM information_schema.triggers
           WHERE trigger_schema=DATABASE() AND LOWER(action_statement) LIKE '%lp_quote_tech_review_item%')
       + (SELECT COUNT(*) FROM information_schema.routines
           WHERE routine_schema=DATABASE() AND routine_name<>'assert_retired_technical_review_ready'
             AND LOWER(routine_definition) LIKE '%lp_quote_tech_review_item%')
    INTO stored_refs;
  IF stored_refs <> 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='TW20_BLOCKED: 数据库对象仍引用旧审核项';
  END IF;

  IF NOT EXISTS (SELECT 1 FROM sys_menu WHERE path='tasks' AND perms='technical:data:task:list') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='TW20_BLOCKED: 新补录工作台菜单不存在';
  END IF;
  SELECT COUNT(*) INTO unexpected_children
    FROM sys_menu child JOIN sys_menu parent ON parent.menu_id=child.parent_id
   WHERE (parent.path='finance-reviews' OR parent.component='technical-data/reviews/index'
          OR parent.perms='technical:data:review:list')
     AND child.perms<>'technical:data:review:decide';
  IF unexpected_children <> 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='TW20_BLOCKED: 旧审核菜单存在其他子项';
  END IF;
  SELECT COUNT(*) INTO duplicate_permissions
    FROM (SELECT perms FROM sys_menu
           WHERE perms IN ('technical:data:review:decide','technical:data:oa:approve')
          GROUP BY perms HAVING COUNT(*) > 1) repeated;
  IF duplicate_permissions <> 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='TW20_BLOCKED: 审批权限菜单重复，须先核实角色授权';
  END IF;
END$$
DELIMITER ;

CALL assert_retired_technical_review_ready();
DROP PROCEDURE assert_retired_technical_review_ready;

SET @technical_workbench_menu := (
  SELECT menu_id FROM sys_menu WHERE path='tasks' AND perms='technical:data:task:list'
  ORDER BY menu_id LIMIT 1
);

-- 将原审批角色授权承接为 OA 审批权限，审批只由 OA 事件处理器执行。
SET @existing_oa_approval_menu := (
  SELECT menu_id FROM sys_menu WHERE perms='technical:data:oa:approve'
  ORDER BY menu_id LIMIT 1
);
INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
SELECT grants.role_id,@existing_oa_approval_menu
  FROM sys_role_menu grants JOIN sys_menu old_menu ON old_menu.menu_id=grants.menu_id
 WHERE @existing_oa_approval_menu IS NOT NULL AND old_menu.perms='technical:data:review:decide';
DELETE grants FROM sys_role_menu grants JOIN sys_menu old_menu ON old_menu.menu_id=grants.menu_id
 WHERE @existing_oa_approval_menu IS NOT NULL AND old_menu.perms='technical:data:review:decide';
DELETE FROM sys_menu
 WHERE @existing_oa_approval_menu IS NOT NULL AND perms='technical:data:review:decide';

UPDATE sys_menu
   SET parent_id=@technical_workbench_menu,menu_name='OA技术资料审批',path='#',component=NULL,
       menu_type='F',visible='1',perms='technical:data:oa:approve',
       remark='允许已映射的部门领导在 OA 事件中审批本人分支',
       update_by='system',update_time=NOW()
 WHERE perms='technical:data:review:decide'
   AND @existing_oa_approval_menu IS NULL;

INSERT INTO sys_menu
  (menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,
   perms,icon,create_by,create_time,update_by,update_time,remark,business_unit_type)
SELECT 'OA技术资料审批',@technical_workbench_menu,3,'#',NULL,1,'0','F','1','0',
       'technical:data:oa:approve','#','system',NOW(),'system',NOW(),
       '允许已映射的部门领导在 OA 事件中审批本人分支',NULL
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='technical:data:oa:approve');

SET @oa_approval_menu := (
  SELECT menu_id FROM sys_menu WHERE perms='technical:data:oa:approve'
  ORDER BY menu_id LIMIT 1
);
INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
SELECT grants.role_id,@oa_approval_menu
  FROM sys_role_menu grants JOIN sys_menu old_menu ON old_menu.menu_id=grants.menu_id
 WHERE old_menu.perms='technical:data:review:list';

DELETE grants FROM sys_role_menu grants JOIN sys_menu old_menu ON old_menu.menu_id=grants.menu_id
 WHERE old_menu.path='finance-reviews' OR old_menu.component='technical-data/reviews/index'
    OR old_menu.perms='technical:data:review:list';
DELETE FROM sys_menu
 WHERE path='finance-reviews' OR component='technical-data/reviews/index'
    OR perms='technical:data:review:list';

DROP TABLE lp_quote_tech_review_item;
