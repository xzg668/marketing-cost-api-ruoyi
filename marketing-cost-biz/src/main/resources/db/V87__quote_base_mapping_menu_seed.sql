-- =====================================================================
-- V87: 报价单基价映射规则菜单与权限 seed
--
-- 说明：
--   1) 只新增菜单和按钮权限，不新增业务表。
--   2) 页面用于维护 lp_quote_base_price_mapping_rule 的识别规则。
--   3) 40421-40425 是当前动态菜单 401xx/402xx 段之后的新增号段，
--      避免占用历史 BOM 菜单 4015、40167-40170。
-- =====================================================================

SET NAMES utf8mb4;

INSERT IGNORE INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status,
   perms, icon, create_by, create_time, update_by, update_time, remark)
VALUES
  (40421, '报价单基价映射规则', 40165, 5, '/price/linked/quote-base-mapping',
   'price/linked/quote-base-mapping/index', 'C', '0', '0',
   'price:quote-base-mapping:list', 'connection', 'admin', NOW(), '', NOW(),
   '维护影响因素文本到 OA 报价单铜基价/锌基价/铝基价的识别规则');

INSERT IGNORE INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status,
   perms, icon, create_by, create_time, update_by, update_time, remark)
VALUES
  (40422, '报价单基价映射查看', 40421, 27, '', NULL, 'F', '0', '0',
   'price:quote-base-mapping:list', '#', 'admin', NOW(), '', NOW(),
   '报价单基价映射规则和识别结果查看'),
  (40423, '报价单基价映射新增', 40421, 28, '', NULL, 'F', '0', '0',
   'price:quote-base-mapping:add', '#', 'admin', NOW(), '', NOW(),
   '新增报价单基价映射规则'),
  (40424, '报价单基价映射编辑', 40421, 29, '', NULL, 'F', '0', '0',
   'price:quote-base-mapping:edit', '#', 'admin', NOW(), '', NOW(),
   '编辑报价单基价映射规则和识别结果'),
  (40425, '报价单基价映射删除', 40421, 30, '', NULL, 'F', '0', '0',
   'price:quote-base-mapping:remove', '#', 'admin', NOW(), '', NOW(),
   '删除报价单基价映射规则');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
VALUES
  (1, 40421), (1, 40422), (1, 40423), (1, 40424), (1, 40425),
  (10, 40421), (10, 40422), (10, 40423), (10, 40424), (10, 40425),
  (11, 40421), (11, 40422), (11, 40423), (11, 40424), (11, 40425);
