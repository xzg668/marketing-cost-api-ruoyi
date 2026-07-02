-- V176: 暂时隐藏“其他费用率对照表”菜单入口
--
-- 说明：
--   1. 只隐藏侧边栏入口，不删除 sys_menu / sys_role_menu。
--   2. 保留 status='0'，避免回退展示时需要重建权限；业务数据表不受影响。

SET NAMES utf8mb4;

UPDATE sys_menu
   SET visible = '1',
       update_by = 'system',
       update_time = NOW(),
       remark = CASE
         WHEN remark IS NULL OR remark = '' THEN 'V176：暂时隐藏其他费用率对照表菜单入口'
         WHEN remark LIKE '%V176：暂时隐藏其他费用率对照表菜单入口%' THEN remark
         ELSE CONCAT(remark, '；V176：暂时隐藏其他费用率对照表菜单入口')
       END
 WHERE menu_type IN ('M', 'C')
   AND (
     menu_id IN (312)
     OR menu_name = '其他费用率对照表'
     OR path IN ('other', '/base/other', 'base/other')
     OR component = 'base/other/index'
     OR perms = 'base:other:list'
   );
