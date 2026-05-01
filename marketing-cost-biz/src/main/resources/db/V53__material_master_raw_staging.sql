-- =============================================================================
-- V53  物料主档 staging 原始表（U9 ItemMaster 16 万行导入用）             2026-04-28
--
-- 强制 connection charset 为 utf8mb4
SET NAMES utf8mb4;
-- =============================================================================
--
-- 业务背景：
--   U9 导出的 ItemMaster (Excel 16 万行 × 62 列) 是物料档案权威数据。
--   先导到 staging 原始表保真，再合并核心字段到 lp_material_master 主表。
--
--   原则：
--     - 全 VARCHAR(255) 容错，原值不丢
--     - material_code + import_batch_id 作为 UK，同一 batch 不重复
--     - 不同 batch 可并存（追溯历史）
--     - 不动 lp_material_master 主表
--
-- 幂等：CREATE TABLE IF NOT EXISTS
-- =============================================================================

CREATE TABLE IF NOT EXISTS lp_material_master_raw (
  id                                BIGINT NOT NULL AUTO_INCREMENT,
  -- 必备：material_code 必填，作为业务唯一键参与 UK
  material_code                     VARCHAR(64)  NOT NULL COMMENT 'C5 物料代码*',
  -- 全 62 列原值（C0 - C61）保真，全 VARCHAR(255)
  finance_category                  VARCHAR(255) NULL COMMENT 'C0 财务分类',
  purchase_category                 VARCHAR(255) NULL COMMENT 'C1 采购分类',
  production_category               VARCHAR(255) NULL COMMENT 'C2 生产分类',
  sales_category                    VARCHAR(255) NULL COMMENT 'C3 销售分类',
  bare_code                         VARCHAR(255) NULL COMMENT 'C4 裸品编码',
  -- material_code C5 已上面定义
  material_name                     VARCHAR(255) NULL COMMENT 'C6 物料名称*',
  material_spec                     VARCHAR(255) NULL COMMENT 'C7 物料规格',
  material_model                    VARCHAR(255) NULL COMMENT 'C8 物料型号',
  drawing_no                        VARCHAR(255) NULL COMMENT 'C9 物料图号',
  main_category_code                VARCHAR(255) NULL COMMENT 'C10 主分类代码',
  main_category_name                VARCHAR(255) NULL COMMENT 'C11 主分类名称',
  unit                              VARCHAR(255) NULL COMMENT 'C12 计量单位',
  shape_attr                        VARCHAR(255) NULL COMMENT 'C13 U9物料形态属性 (采购件/制造件/委外件)',
  min_eco_batch                     VARCHAR(255) NULL COMMENT 'C14 最小经济批量',
  department_code                   VARCHAR(255) NULL COMMENT 'C15 部门代码',
  department_name                   VARCHAR(255) NULL COMMENT 'C16 部门名称',
  production_division               VARCHAR(255) NULL COMMENT 'C17 生产事业部名称',
  purchase_lead_time                VARCHAR(255) NULL COMMENT 'C18 采购处理提前期',
  purchase_post_lead_time           VARCHAR(255) NULL COMMENT 'C19 采购后处理提前期',
  legacy_u9_code                    VARCHAR(255) NULL COMMENT 'C20 老U9物料代码',
  global_seg_14_customs_unit        VARCHAR(255) NULL COMMENT 'C21 全局段14(海关单位)',
  global_seg_15_package_size        VARCHAR(255) NULL COMMENT 'C22 全局段15(包装尺寸)',
  global_seg_17_replace_strategy    VARCHAR(255) NULL COMMENT 'C23 全局段17(替代策略)',
  global_seg_18_purchase_type       VARCHAR(255) NULL COMMENT 'C24 全局段18(采购类型)',
  global_seg_19_in_out_ratio        VARCHAR(255) NULL COMMENT 'C25 全局段19(内外采比例)',
  global_seg_2_logistics_type       VARCHAR(255) NULL COMMENT 'C26 全局段2(物流采购类型)',
  global_seg_20_internal_threshold  VARCHAR(255) NULL COMMENT 'C27 全局段20(内部采购阈值)',
  private_seg_21_customs_name       VARCHAR(255) NULL COMMENT 'C28 私有段21(海关名称)',
  private_seg_22_customs_code       VARCHAR(255) NULL COMMENT 'C29 私有段22(海关编码)',
  private_seg_23_customs_desc       VARCHAR(255) NULL COMMENT 'C30 私有段23(海关描述)',
  private_seg_24_product_property   VARCHAR(255) NULL COMMENT 'C31 私有段24(产品属性)',
  private_seg_25_daily_capacity     VARCHAR(255) NULL COMMENT 'C32 私有段25(日产能)',
  private_seg_26_lead_time          VARCHAR(255) NULL COMMENT 'C33 私有段26(加工周期)',
  global_seg_3_status               VARCHAR(255) NULL COMMENT 'C34 全局段3(验证/正式)',
  global_seg_4_material             VARCHAR(255) NULL COMMENT 'C35 全局段4(材质)',
  global_seg_5_net_weight           VARCHAR(255) NULL COMMENT 'C36 全局段5(净重)',
  global_seg_6_valid_period         VARCHAR(255) NULL COMMENT 'C37 全局段6(有效期)',
  global_seg_7_product_property_class VARCHAR(255) NULL COMMENT 'C38 全局段7(产品属性分类)',
  global_seg_8_loss_rate            VARCHAR(255) NULL COMMENT 'C39 全局段8(净损失率)',
  global_seg_9_gross_weight         VARCHAR(255) NULL COMMENT 'C40 全局段9(单品毛重)',
  purchase_multiple                 VARCHAR(255) NULL COMMENT 'C41 采购倍量',
  min_order_qty                     VARCHAR(255) NULL COMMENT 'C42 最小叫货量',
  default_supplier                  VARCHAR(255) NULL COMMENT 'C43 默认主供应商',
  default_buyer                     VARCHAR(255) NULL COMMENT 'C44 默认采购员',
  plan_method                       VARCHAR(255) NULL COMMENT 'C45 计划方法',
  forecast_control_type             VARCHAR(255) NULL COMMENT 'C46 预测控制类型',
  demand_trace                      VARCHAR(255) NULL COMMENT 'C47 是否需求追溯',
  demand_category_control           VARCHAR(255) NULL COMMENT 'C48 按照需求分类控制',
  demand_category_compare_rule      VARCHAR(255) NULL COMMENT 'C49 需求分类对比规则',
  default_planner                   VARCHAR(255) NULL COMMENT 'C50 默认计划员',
  engineering_change_control        VARCHAR(255) NULL COMMENT 'C51 工程变更控制',
  allow_over_pick                   VARCHAR(255) NULL COMMENT 'C52 允许超额领料',
  prepare_over_type                 VARCHAR(255) NULL COMMENT 'C53 备料超额类型',
  over_complete_type                VARCHAR(255) NULL COMMENT 'C54 可超量完工类型',
  over_complete_ratio               VARCHAR(255) NULL COMMENT 'C55 完工超额比例',
  inventory_planning_method         VARCHAR(255) NULL COMMENT 'C56 库存规划方法',
  code_inventory_account            VARCHAR(255) NULL COMMENT 'C57 番号存货核算',
  cost_element                      VARCHAR(255) NULL COMMENT 'C58 成本要素 (BOM 规则用)',
  producible                        VARCHAR(255) NULL COMMENT 'C59 可生产',
  purchase_receive_principle        VARCHAR(255) NULL COMMENT 'C60 料品采购相关信息.收货原则',
  mrp_purchase_pre_lead_time        VARCHAR(255) NULL COMMENT 'C61 料品MRP相关信息.采购预处理提前期(天)',
  -- 元数据
  import_batch_id                   VARCHAR(64)  NOT NULL COMMENT '导入批次（同一文件多次导入区分）',
  imported_at                       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_raw_material_batch (material_code, import_batch_id),
  KEY idx_raw_batch (import_batch_id),
  KEY idx_raw_shape (shape_attr),
  KEY idx_raw_cost_element (cost_element),
  KEY idx_raw_main_cat (main_category_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物料主档 U9 ItemMaster 原始导入表（保真，不破坏 lp_material_master 主表）';
