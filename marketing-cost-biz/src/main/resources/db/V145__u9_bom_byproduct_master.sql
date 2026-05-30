-- =============================================================================
-- V145: U9 BOM 母项副产品档案
-- -----------------------------------------------------------------------------
-- 数据来源：BOMMaster20260522-副产品.xlsx / U9 后续接口。
-- 幂等口径：不引入导入批次，按
--   BOM 生产目的 + 母件料号 + 副产品料号 + 生效日期 + 失效日期
-- 去重；重复导入同一自然键时更新业务字段和导入追溯字段。
-- =============================================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS lp_u9_bom_byproduct_master (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  parent_material_no VARCHAR(64) NOT NULL COMMENT '母件料品_料号',
  parent_material_name VARCHAR(255) NULL COMMENT '母件料品_品名',
  parent_material_spec VARCHAR(255) NULL COMMENT '母件料品.规格',
  bom_purpose VARCHAR(64) NOT NULL COMMENT 'BOM生产目的',
  version_no VARCHAR(64) NULL COMMENT '版本号',
  output_type VARCHAR(64) NULL COMMENT '等级品产出比例.产出类型',
  byproduct_material_no VARCHAR(64) NOT NULL COMMENT '等级品产出比例.料品.料号；业务展示为副产品料号',
  byproduct_material_name VARCHAR(255) NULL COMMENT '等级品产出比例.料品.料品名称',
  operation_no VARCHAR(64) NULL COMMENT '等级品产出比例.工序号',
  output_qty DECIMAL(20,8) NULL COMMENT '等级品产出比例.产出数量',
  unit VARCHAR(64) NULL COMMENT '等级品产出比例.单位',
  status VARCHAR(64) NULL COMMENT '状态',
  production_dept_code VARCHAR(64) NULL COMMENT '母件料品.生产部门，第13列',
  production_dept_name VARCHAR(255) NULL COMMENT '母件料品.生产部门，第14列',
  effective_from DATE NOT NULL COMMENT '生效日期',
  effective_to DATE NOT NULL COMMENT '失效日期',
  u9_created_by VARCHAR(128) NULL COMMENT '创建人',
  u9_created_time DATETIME NULL COMMENT '创建时间',
  source_type VARCHAR(32) NOT NULL DEFAULT 'EXCEL' COMMENT '来源类型：EXCEL/API/U9_API/MQ/SCHEDULE',
  source_file_name VARCHAR(255) NULL COMMENT '最近一次导入来源文件名',
  imported_by VARCHAR(128) NULL COMMENT '最近一次导入人',
  imported_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近一次导入时间',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_u9_bom_byproduct_natural (
    bom_purpose,
    parent_material_no,
    byproduct_material_no,
    effective_from,
    effective_to
  ),
  KEY idx_u9_bom_byproduct_parent (parent_material_no),
  KEY idx_u9_bom_byproduct_child (byproduct_material_no),
  KEY idx_u9_bom_byproduct_effective (bom_purpose, effective_from, effective_to),
  KEY idx_u9_bom_byproduct_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='U9 BOM母项副产品档案；按自然键幂等导入，无批次概念';

INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type,
   visible, status, perms, icon, create_by, create_time, update_by, update_time, remark,
   business_unit_type)
VALUES
  (40469, 'BOM副产品档案', 40435, 2, '/base/u9/bom-byproduct',
   'pages:U9BomByproductPage', 1, '0', 'C',
   '0', '0', 'base:u9-bom-byproduct:list', 'Tickets', 'admin', NOW(), '', NOW(),
   'U9 BOM母项副产品档案导入和查询入口', NULL),
  (40470, 'BOM副产品查询', 40469, 1, '', NULL, 1, '0', 'F',
   '0', '0', 'base:u9-bom-byproduct:list', '#', 'admin', NOW(), '', NOW(),
   'U9 BOM副产品档案列表查询权限', NULL),
  (40471, 'BOM副产品导入', 40469, 2, '', NULL, 1, '0', 'F',
   '0', '0', 'base:u9-bom-byproduct:import', '#', 'admin', NOW(), '', NOW(),
   'U9 BOM副产品档案 Excel 导入权限', NULL),
  (40472, 'BOM副产品字段映射', 40469, 3, '', NULL, 1, '0', 'F',
   '0', '0', 'base:u9-bom-byproduct:export', '#', 'admin', NOW(), '', NOW(),
   'U9 BOM副产品档案字段映射查看权限', NULL)
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
  (1, 40469), (1, 40470), (1, 40471), (1, 40472),
  (10, 40469), (10, 40470), (10, 40471), (10, 40472),
  (11, 40469), (11, 40470), (11, 40471), (11, 40472);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40469
FROM sys_role_menu
WHERE menu_id IN (40435);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40470
FROM sys_role_menu
WHERE menu_id IN (40469);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40471
FROM sys_role_menu
WHERE menu_id IN (40469);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40472
FROM sys_role_menu
WHERE menu_id IN (40469);
