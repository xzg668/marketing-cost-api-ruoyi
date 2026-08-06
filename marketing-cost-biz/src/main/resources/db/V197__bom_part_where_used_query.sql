-- =============================================================================
-- V197: 当前有效 BOM 物料使用查询
-- ---------------------------------------------------------------------------
-- 数据由 EasyData 按“组织 + 零件 + 顶层产品 + BOM用途”聚合后同步。
-- 页面只表达当前 BOM 潜在影响，不与历史已确认报价快照混用。
-- =============================================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS lp_bom_part_where_used (
  id                       BIGINT NOT NULL AUTO_INCREMENT,
  relation_key             VARCHAR(64) NOT NULL COMMENT 'EasyData关系唯一键',
  price_org_code           VARCHAR(32) NOT NULL COMMENT 'U9报价组织：210/220',
  part_code                VARCHAR(64) NOT NULL COMMENT '被反查物料料号',
  part_name                VARCHAR(255) NULL COMMENT '物料名称',
  part_spec                VARCHAR(500) NULL COMMENT '物料规格',
  top_product_code         VARCHAR(64) NOT NULL COMMENT '受影响顶层产品料号',
  top_product_name         VARCHAR(255) NULL COMMENT '顶层产品名称',
  top_bom_version          VARCHAR(64) NULL COMMENT '顶层产品当前BOM版本',
  bom_purpose              VARCHAR(32) NOT NULL DEFAULT '主制造' COMMENT 'BOM用途',
  total_qty_per_top        DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '累计单台用量',
  bom_path_count           BIGINT NOT NULL DEFAULT 0 COMMENT 'BOM路径数量',
  min_level                INT NULL COMMENT '最浅出现层级',
  max_level                INT NULL COMMENT '最深出现层级',
  has_leaf_occurrence      TINYINT NOT NULL DEFAULT 0 COMMENT '是否作为叶子节点出现',
  has_non_leaf_occurrence  TINYINT NOT NULL DEFAULT 0 COMMENT '是否作为中间件出现',
  sample_path              VARCHAR(2000) NULL COMMENT '一条示例BOM路径',
  shape_attr               VARCHAR(64) NULL COMMENT '形态属性',
  source_category          VARCHAR(64) NULL COMMENT '生产分类',
  cost_element_code        VARCHAR(64) NULL COMMENT '成本要素编码',
  source_import_batch_id   VARCHAR(128) NULL COMMENT 'U9来源同步批次',
  build_batch_id           VARCHAR(128) NULL COMMENT '层级构建批次',
  source_built_at          DATETIME NULL COMMENT '层级构建时间',
  snapshot_date            DATE NOT NULL COMMENT '数据快照日期',
  created_at               DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at               DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                             ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_bom_part_where_used_relation (relation_key),
  KEY idx_bom_where_used_part (price_org_code, part_code),
  KEY idx_bom_where_used_product (price_org_code, top_product_code),
  KEY idx_bom_where_used_snapshot (snapshot_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='当前有效BOM物料使用关系，来源EasyData';

INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache,
   menu_type, visible, status, perms, icon, create_by, create_time, update_by,
   update_time, remark, business_unit_type)
VALUES
  (40480, '物料使用查询', 40435, 35, '/base/u9/material-usage',
   'pages:MaterialUsageQueryPage', 1, '0', 'C', '0', '0',
   'base:u9-material-usage:list', 'Search', 'admin', NOW(), '', NOW(),
   '按物料查询当前有效U9 BOM中的受影响顶层产品', NULL),
  (40481, '物料使用查询权限', 40480, 1, '', NULL, 1, '0', 'F', '0', '0',
   'base:u9-material-usage:list', '#', 'admin', NOW(), '', NOW(),
   '物料使用关系分页查询权限', NULL)
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
SELECT DISTINCT role_id, 40480
FROM sys_role_menu
WHERE menu_id = 40435;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40481
FROM sys_role_menu
WHERE menu_id = 40480;
