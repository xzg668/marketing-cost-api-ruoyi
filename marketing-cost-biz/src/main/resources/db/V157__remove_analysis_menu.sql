-- =====================================================================
-- V157: 移除“数据分析”菜单
--
-- 说明：
--   1) 当前业务先不展示“数据分析 / 成本分析 / 报表”侧边栏入口。
--   2) 历史库中 menu_id=40162 被“数据分析”顶级目录占用，和 V79 的“联动价编辑”
--      按钮权限 id 冲突；删除数据分析目录后，补回该按钮权限。
-- =====================================================================

SET NAMES utf8mb4;

DELETE FROM sys_role_menu
 WHERE menu_id IN (
   SELECT menu_id
     FROM sys_menu
    WHERE (menu_id IN (40162, 40195, 40196, 600, 601, 602)
       OR menu_name IN ('数据分析', '成本分析', '报表')
       OR path = 'analysis'
       OR component LIKE 'analysis/%'
       OR perms LIKE 'analysis:%')
      AND (menu_type IN ('M', 'C') OR parent_id IN (40162, 600))
 );

DELETE FROM sys_menu
 WHERE (menu_id IN (40162, 40195, 40196, 600, 601, 602)
    OR menu_name IN ('数据分析', '成本分析', '报表')
    OR path = 'analysis'
    OR component LIKE 'analysis/%'
    OR perms LIKE 'analysis:%')
   AND (menu_type IN ('M', 'C') OR parent_id IN (40162, 600));

INSERT IGNORE INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status,
   perms, icon, create_by, create_time, update_by, update_time, remark)
VALUES
  (40162, '联动价编辑', 401, 22, '', NULL, 'F', '0', '0',
   'price:linked-item:edit', '#', 'admin', NOW(), '', NOW(),
   '联动价格表编辑和公式修改');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
VALUES
  (1, 40162),
  (10, 40162),
  (11, 40162);
