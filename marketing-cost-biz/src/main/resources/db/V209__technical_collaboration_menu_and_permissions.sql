-- QCBP-09：登录态技术协作入口。OA尚未接入，本迁移不创建用户、不写业务数据。
SET NAMES utf8mb4;

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type,
   visible, status, perms, icon, create_by, create_time, update_by, update_time, remark,
   business_unit_type)
SELECT
  '协作入口', 0, 60, 'collaboration', 'Layout', 1, '0', 'M',
  '0', '0', NULL, 'connection', 'system', NOW(), 'system', NOW(),
  'BOM、包装和价格技术协作入口', NULL
WHERE NOT EXISTS (
  SELECT 1 FROM sys_menu WHERE parent_id = 0 AND path = 'collaboration'
);

SET @qcbp_collaboration_parent_id := (
  SELECT menu_id FROM sys_menu
  WHERE parent_id = 0 AND path = 'collaboration'
  ORDER BY menu_id LIMIT 1
);

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type,
   visible, status, perms, icon, create_by, create_time, update_by, update_time, remark,
   business_unit_type)
SELECT
  '技术协作', @qcbp_collaboration_parent_id, 1, 'tasks',
  'collaboration/technical/index', 1, '0', 'C',
  '0', '0', 'collaboration:task:read', 'list', 'system', NOW(), 'system', NOW(),
  '技术人员仅查看本人被分配的产品任务', NULL
WHERE @qcbp_collaboration_parent_id IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM sys_menu WHERE component = 'collaboration/technical/index'
  );

SET @qcbp_technical_menu_id := (
  SELECT menu_id FROM sys_menu
  WHERE component = 'collaboration/technical/index'
  ORDER BY menu_id LIMIT 1
);

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type,
   visible, status, perms, icon, create_by, create_time, update_by, update_time, remark,
   business_unit_type)
SELECT
  '技术任务编辑', @qcbp_technical_menu_id, 1, '#', NULL, 1, '0', 'F',
  '1', '0', 'collaboration:task:edit', '#', 'system', NOW(), 'system', NOW(),
  '开始任务和执行技术完整性校验', NULL
WHERE @qcbp_technical_menu_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'collaboration:task:edit');

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type,
   visible, status, perms, icon, create_by, create_time, update_by, update_time, remark,
   business_unit_type)
SELECT
  '技术任务提交', @qcbp_technical_menu_id, 2, '#', NULL, 1, '0', 'F',
  '1', '0', 'collaboration:task:submit', '#', 'system', NOW(), 'system', NOW(),
  '完整性校验通过后提交技术任务', NULL
WHERE @qcbp_technical_menu_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'collaboration:task:submit');

-- 协作角色是受限角色：只保留本次协作入口及其权限，不继承报价、价格或系统管理菜单。
DELETE role_menu
FROM sys_role_menu role_menu
JOIN sys_role role ON role.role_id = role_menu.role_id
WHERE LOWER(role.role_key) = 'oa_collaborator'
  AND role_menu.menu_id NOT IN (
    @qcbp_collaboration_parent_id,
    @qcbp_technical_menu_id,
    COALESCE((SELECT menu_id FROM sys_menu WHERE perms = 'collaboration:task:edit' ORDER BY menu_id LIMIT 1), -1),
    COALESCE((SELECT menu_id FROM sys_menu WHERE perms = 'collaboration:task:submit' ORDER BY menu_id LIMIT 1), -1)
  );

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role.role_id, menu.menu_id
FROM sys_role role
JOIN sys_menu menu ON menu.menu_id IN (
  @qcbp_collaboration_parent_id,
  @qcbp_technical_menu_id,
  COALESCE((SELECT menu_id FROM sys_menu WHERE perms = 'collaboration:task:edit' ORDER BY menu_id LIMIT 1), -1),
  COALESCE((SELECT menu_id FROM sys_menu WHERE perms = 'collaboration:task:submit' ORDER BY menu_id LIMIT 1), -1)
)
WHERE LOWER(role.role_key) = 'oa_collaborator'
  AND role.status = '0'
  AND role.del_flag = '0';
