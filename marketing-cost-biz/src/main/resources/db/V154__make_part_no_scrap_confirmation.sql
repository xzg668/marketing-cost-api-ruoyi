-- =============================================================================
-- V154: 制造件无废料人工确认表和价格准备权限
-- -----------------------------------------------------------------------------
-- 说明：
--   1. 缺废料映射不能静默按 0 处理，必须由价格准备缺口清单发起人工确认并留痕。
--   2. 确认的是“料号在生效期间无废料”，不是某次计算临时放行。
--   3. 有废料的料号仍必须维护 lp_material_scrap_ref，本表不替代 CMS 废料映射。
-- =============================================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS lp_make_part_no_scrap_confirmation (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  business_unit_type VARCHAR(32) NOT NULL DEFAULT '' COMMENT '业务单元类型',
  material_no VARCHAR(64) NOT NULL COMMENT '被确认无废料的制造件子项/原材料料号',
  material_name VARCHAR(180) DEFAULT NULL COMMENT '料号名称，冗余展示',
  effective_from_month VARCHAR(7) NOT NULL COMMENT '生效起始月份 YYYY-MM',
  effective_to_month VARCHAR(7) DEFAULT NULL COMMENT '生效结束月份 YYYY-MM；为空表示持续有效',
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/REVOKED',
  confirm_reason VARCHAR(500) NOT NULL COMMENT '人工确认原因',
  source_oa_no VARCHAR(64) DEFAULT NULL COMMENT '首次触发确认的OA单号',
  source_gap_id BIGINT DEFAULT NULL COMMENT '首次触发确认的价格准备缺口ID',
  confirmed_by VARCHAR(64) DEFAULT NULL COMMENT '确认人',
  confirmed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '确认时间',
  revoked_by VARCHAR(64) DEFAULT NULL COMMENT '撤销人',
  revoked_at DATETIME DEFAULT NULL COMMENT '撤销时间',
  revoke_reason VARCHAR(500) DEFAULT NULL COMMENT '撤销原因',
  active_effective_from_month VARCHAR(7)
    GENERATED ALWAYS AS (
      CASE WHEN status = 'ACTIVE' THEN effective_from_month ELSE NULL END
    ) STORED COMMENT '仅用于限制同一料号同一开始月只能有一条ACTIVE确认',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_no_scrap_active_month (
    business_unit_type,
    material_no,
    active_effective_from_month
  ),
  KEY idx_no_scrap_material_period (
    business_unit_type,
    material_no,
    effective_from_month,
    effective_to_month,
    status
  ),
  KEY idx_no_scrap_source_gap (source_gap_id),
  KEY idx_no_scrap_source_oa (source_oa_no),
  KEY idx_no_scrap_status (status),
  KEY idx_no_scrap_confirmed_at (confirmed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='制造件无废料人工确认表';

INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type,
   visible, status, perms, icon, create_by, create_time, update_by, update_time, remark,
   business_unit_type)
VALUES
  (40473, '价格准备 确认无废料', 40454, 7, '', NULL, 1, '0', 'F',
   '0', '0', 'cost:price-prepare:no-scrap-confirm', '#', 'admin', NOW(), '', NOW(),
   '价格准备缺口中人工确认无废料并按0抵扣权限', NULL),
  (40474, '价格准备 撤销无废料确认', 40454, 8, '', NULL, 1, '0', 'F',
   '0', '0', 'cost:price-prepare:no-scrap-revoke', '#', 'admin', NOW(), '', NOW(),
   '撤销价格准备无废料人工确认权限', NULL)
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
  (1, 40473), (1, 40474),
  (10, 40473), (10, 40474),
  (11, 40473), (11, 40474);

-- 兼容已有价格准备生成角色：能生成价格准备的角色默认可确认和撤销无废料确认。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40473
FROM sys_role_menu
WHERE menu_id IN (40456, 40460);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40474
FROM sys_role_menu
WHERE menu_id IN (40456, 40460);
