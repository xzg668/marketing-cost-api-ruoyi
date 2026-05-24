-- =============================================================================
-- V109: 新增价格准备菜单和权限
-- -----------------------------------------------------------------------------
-- 菜单位置：成本核算 / 价格准备，排序在实时成本计算前。
-- 说明：价格准备是成本运行前的显式数据准备入口，不放在价格源管理下。
-- =============================================================================

SET NAMES utf8mb4;

DELETE FROM sys_role_menu
WHERE menu_id IN (
  SELECT menu_id
  FROM sys_menu
  WHERE path = '/cost/price-prepare'
     OR component = 'cost/price-prepare/index'
     OR perms LIKE 'cost:price-prepare:%'
);

DELETE FROM sys_menu
WHERE path = '/cost/price-prepare'
   OR component = 'cost/price-prepare/index'
   OR perms LIKE 'cost:price-prepare:%';

-- 显式设置既有成本核算子菜单顺序，避免开发环境手动重复执行时 order_num 持续累加。
UPDATE sys_menu c
JOIN (
  SELECT menu_id
  FROM sys_menu
  WHERE path IN ('cost', '/cost')
     OR menu_name = '成本核算'
  ORDER BY CASE WHEN menu_id = 40161 THEN 0 WHEN path IN ('cost', '/cost') THEN 1 ELSE 2 END, menu_id
  LIMIT 1
) p ON c.parent_id = p.menu_id
SET c.order_num = CASE
      WHEN c.path IN ('/cost/run', 'run') OR c.menu_name = '实时成本计算' THEN 2
      WHEN c.path IN ('/cost/run/completed', 'run/completed') OR c.menu_name = '已核算成本明细' THEN 3
      ELSE c.order_num
    END,
    c.update_time = NOW()
WHERE c.menu_type = 'C'
  AND (
    c.path IN ('/cost/run', 'run', '/cost/run/completed', 'run/completed')
    OR c.menu_name IN ('实时成本计算', '已核算成本明细')
  );

INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type,
   visible, status, perms, icon, create_by, create_time, update_by, update_time, remark,
   business_unit_type)
SELECT
  40454, '价格准备', p.menu_id, 1, '/cost/price-prepare', 'cost/price-prepare/index',
  1, '0', 'C', '0', '0', 'cost:price-prepare:list', 'Operation', 'admin', NOW(), '', NOW(),
  '成本核算前的价格准备入口：生成普通料号、包装组件、自制件价格准备结果和缺口清单', NULL
FROM (
  SELECT menu_id
  FROM sys_menu
  WHERE path IN ('cost', '/cost')
     OR menu_name = '成本核算'
  ORDER BY CASE WHEN menu_id = 40161 THEN 0 WHEN path IN ('cost', '/cost') THEN 1 ELSE 2 END, menu_id
  LIMIT 1
) p;

INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type,
   visible, status, perms, icon, create_by, create_time, update_by, update_time, remark,
   business_unit_type)
VALUES
  (40455, '价格准备 查询', 40454, 1, '', NULL, 1, '0', 'F',
   '0', '0', 'cost:price-prepare:list', '#', 'admin', NOW(), '', NOW(),
   '价格准备批次、明细和缺口查询权限', NULL),
  (40456, '价格准备 生成', 40454, 2, '', NULL, 1, '0', 'F',
   '0', '0', 'cost:price-prepare:generate', '#', 'admin', NOW(), '', NOW(),
   '按 OA 单生成价格准备数据权限', NULL),
  (40457, '价格准备 明细', 40454, 3, '', NULL, 1, '0', 'F',
   '0', '0', 'cost:price-prepare:detail', '#', 'admin', NOW(), '', NOW(),
   '价格准备明细查看权限', NULL),
  (40458, '价格准备 缺口', 40454, 4, '', NULL, 1, '0', 'F',
   '0', '0', 'cost:price-prepare:gap', '#', 'admin', NOW(), '', NOW(),
   '价格准备缺口清单查看权限', NULL),
  (40459, '价格准备 查看全部', 40454, 5, '', NULL, 1, '0', 'F',
   '0', '0', 'cost:price-prepare:list-all', '#', 'admin', NOW(), '', NOW(),
   '查看全部报价员价格准备候选权限', NULL),
  (40460, '价格准备 生成全部', 40454, 6, '', NULL, 1, '0', 'F',
   '0', '0', 'cost:price-prepare:generate-all', '#', 'admin', NOW(), '', NOW(),
   '生成全部报价员价格准备候选权限', NULL)
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

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
VALUES
  (1, 40454), (1, 40455), (1, 40456), (1, 40457), (1, 40458), (1, 40459), (1, 40460),
  (10, 40454), (10, 40455), (10, 40456), (10, 40457), (10, 40458),
  (11, 40454), (11, 40455), (11, 40456), (11, 40457), (11, 40458);

-- 兼容已有自定义角色：已能看到成本核算的角色，自动补齐价格准备菜单和按钮权限。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40454
FROM sys_role_menu
WHERE menu_id IN (
  SELECT menu_id
  FROM sys_menu
  WHERE path IN ('cost', '/cost')
     OR menu_name = '成本核算'
);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40455
FROM sys_role_menu
WHERE menu_id = 40454;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40456
FROM sys_role_menu
WHERE menu_id = 40454;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40457
FROM sys_role_menu
WHERE menu_id = 40454;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40458
FROM sys_role_menu
WHERE menu_id = 40454;

-- 查看全部、生成全部仅默认授予管理员；普通价格准备角色需显式授权。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
VALUES
  (1, 40459),
  (1, 40460);
