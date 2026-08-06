-- =====================================================================
-- V201: 报价 BOM 标准/替代选择权限
--
-- 只新增按钮权限，不新增业务表，不改变现有报价查看权限：
-- 1. ingest:quote:list 继续负责查看标准/替代关系；
-- 2. quote:costing:bom:alternative-select 负责切换、恢复和查看选择历史；
-- 3. 默认只授予超级管理员，其他报价角色由管理员按岗位授权。
-- =====================================================================

SET NAMES utf8mb4;

INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status,
   perms, icon, create_by, create_time, update_by, update_time, remark)
VALUES
  (40482, '报价BOM标准替代选择', 206, 20, '', NULL, 'F', '0', '0',
   'quote:costing:bom:alternative-select', '#', 'admin', NOW(), '', NOW(),
   '选择替代件、恢复标准件和查看选择历史')
ON DUPLICATE KEY UPDATE
  menu_name = VALUES(menu_name),
  parent_id = VALUES(parent_id),
  order_num = VALUES(order_num),
  perms = VALUES(perms),
  update_time = NOW(),
  remark = VALUES(remark);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
VALUES (1, 40482);
