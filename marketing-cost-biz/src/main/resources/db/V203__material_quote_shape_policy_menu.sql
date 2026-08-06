-- =============================================================================
-- V203: 报价 BOM 物料形态规则菜单与权限
-- -----------------------------------------------------------------------------
-- 菜单位置：规则配置 -> BOM 结算规则之后 -> 报价 BOM 物料形态规则。
-- 本迁移只增加菜单和权限，不修改规则、最终 BOM 或历史报价数据。
-- =============================================================================

SET NAMES utf8mb4;

INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache,
   menu_type, visible, status, perms, icon, create_by, create_time, update_by,
   update_time, remark, business_unit_type)
VALUES
  (40483, '报价 BOM 物料形态规则', 40475, 15,
   '/rules/material-quote-shape-policy', 'pages:MaterialQuoteShapePolicyPage',
   1, '0', 'C', '0', '0', 'bom-data:material-shape-policy:list', '#',
   'admin', NOW(), '', NOW(),
   '维护按月份生效的固定形态和供应商比例形态规则；不改变已冻结报价', NULL),
  (40484, '报价BOM形态规则查看', 40483, 1, '', NULL,
   1, '0', 'F', '0', '0', 'bom-data:material-shape-policy:list', '#',
   'admin', NOW(), '', NOW(), '料品报价形态规则查看权限', NULL),
  (40485, '报价BOM形态规则编辑', 40483, 2, '', NULL,
   1, '0', 'F', '0', '0', 'bom-data:material-shape-policy:edit', '#',
   'admin', NOW(), '', NOW(), '料品报价形态规则新增、修改和删除权限', NULL),
  (40486, '报价BOM形态规则启停', 40483, 3, '', NULL,
   1, '0', 'F', '0', '0', 'bom-data:material-shape-policy:toggle', '#',
   'admin', NOW(), '', NOW(), '料品报价形态规则启用和停用权限', NULL)
ON DUPLICATE KEY UPDATE
  menu_name = VALUES(menu_name),
  parent_id = VALUES(parent_id),
  order_num = VALUES(order_num),
  path = VALUES(path),
  component = VALUES(component),
  is_frame = VALUES(is_frame),
  is_cache = VALUES(is_cache),
  menu_type = VALUES(menu_type),
  visible = VALUES(visible),
  status = VALUES(status),
  perms = VALUES(perms),
  icon = VALUES(icon),
  update_time = NOW(),
  remark = VALUES(remark);

-- 默认只授予超级管理员；其他岗位由管理员按职责授权。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
VALUES (1, 40475), (1, 40483), (1, 40484), (1, 40485), (1, 40486);
