-- =============================================================================
-- V40  BOM 三层架构 + 过滤规则表                                      2026-04-23
-- -----------------------------------------------------------------------------
-- 本脚本仅建新表 + 初始规则数据，不修改老表 lp_bom_manage_item
-- 执行环境：MySQL 8.4+，utf8mb4 默认字符集，当前库 marketing_cost
-- 幂等：4 张 CREATE TABLE 带 IF NOT EXISTS；INSERT 使用 WHERE NOT EXISTS 防重
-- 对应任务：bom-tasks/T1-ddl.md
-- 父设计：bom-three-layer-architecture-2026-04-23.md §附录 C
-- =============================================================================


-- ---------------------------------------------------------------------------
-- 第 1 层 · ODS 原始层：U9/Excel 导入落地，33 列原样保留，不做任何业务加工
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS lp_bom_u9_source (
  id                      BIGINT        PRIMARY KEY AUTO_INCREMENT,

  -- 技术字段（追溯导入批次 / 数据源）
  import_batch_id         VARCHAR(64)   NOT NULL COMMENT '导入批次 UUID',
  source_type             VARCHAR(16)   NOT NULL COMMENT 'EXCEL / U9_API',
  source_file_name        VARCHAR(255)  NULL     COMMENT 'Excel 原文件名',
  imported_at             DATETIME      NOT NULL COMMENT '导入时间',
  imported_by             VARCHAR(64)   NULL     COMMENT '导入人用户名',

  -- U9 原样字段（33 列，按 U9 导出顺序）
  parent_material_no      VARCHAR(32)   NOT NULL COMMENT '母件料品_料号',
  parent_material_name    VARCHAR(128)  NULL     COMMENT '母件料品_品名',
  production_unit         VARCHAR(16)   NULL     COMMENT '生产单位',
  bom_purpose             VARCHAR(16)   NULL     COMMENT 'BOM 生产目的 普机/主制造/精益',
  bom_version             VARCHAR(16)   NULL     COMMENT 'BOM 版本号 F001 等',
  bom_status              VARCHAR(16)   NULL     COMMENT 'BOM 状态 已核准 等',
  child_seq               INT           NULL     COMMENT '子件项次',
  child_type              VARCHAR(16)   NULL     COMMENT '子项类型 标准 等',
  child_material_no       VARCHAR(32)   NOT NULL COMMENT '子件.料号',
  child_material_name     VARCHAR(128)  NULL     COMMENT '子项_品名',
  child_material_spec     VARCHAR(256)  NULL     COMMENT '子项规格',
  cost_element_code       VARCHAR(32)   NULL     COMMENT '成本要素编码',
  cost_element_name       VARCHAR(64)   NULL     COMMENT '成本要素.名称',
  consign_source          VARCHAR(32)   NULL     COMMENT '委托加工备料来源',
  u9_is_cost_flag         TINYINT(1)    NULL     COMMENT '是否计算成本：1=√，0=空，NULL=未知（参考不权威）',
  engineering_change_no   VARCHAR(32)   NULL     COMMENT '工程变更单编码',
  issue_unit              VARCHAR(16)   NULL     COMMENT '发料单位',
  stock_unit              VARCHAR(16)   NULL     COMMENT '库存主单位',
  qty_per_parent          DECIMAL(20,8) NULL     COMMENT '子项_用量 相对直接父',
  process_seq             VARCHAR(16)   NULL     COMMENT '工序号',
  material_category_1     VARCHAR(32)   NULL     COMMENT '子件.主分类 第 1 列',
  material_category_2     VARCHAR(32)   NULL     COMMENT '子件.主分类 第 2 列',
  production_category     VARCHAR(32)   NULL     COMMENT '生产分类 制造件/采购件/半成品',
  shape_attr              VARCHAR(32)   NULL     COMMENT '形态属性',
  production_dept         VARCHAR(64)   NULL     COMMENT '生产部门',
  issue_method            VARCHAR(32)   NULL     COMMENT '发料方式',
  is_virtual              TINYINT(1)    NULL     COMMENT '是否虚拟',
  parent_base_qty         DECIMAL(20,8) NULL     COMMENT '母件底数',
  segment3                VARCHAR(32)   NULL     COMMENT '段 3 替代策略',
  segment4                VARCHAR(32)   NULL     COMMENT '段 4 工序编号',
  order_complete          TINYINT(1)    NULL     COMMENT '订单完工',
  effective_from          DATE          NULL     COMMENT 'U9 生效日期',
  effective_to            DATE          NULL     COMMENT 'U9 失效日期 9999-12-31 = 当前生效',

  -- 唯一键：同一批次内 (父, 子, 目的, 项次) 不能重复
  UNIQUE KEY uk_batch_relation (import_batch_id, parent_material_no, child_material_no, bom_purpose, child_seq),
  INDEX idx_parent_purpose_effective (parent_material_no, bom_purpose, effective_to),
  INDEX idx_child                    (child_material_no),
  INDEX idx_import_batch             (import_batch_id)
) COMMENT='BOM U9 原始层 ODS：Excel/U9 API 导入落地，33 列原样保留';


-- ---------------------------------------------------------------------------
-- 第 2 层 · DWD 事实层：在 U9 单层父子基础上派生 path / level / qty_per_top
--                       版本 append-only，多版本并存
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS lp_bom_raw_hierarchy (
  id                         BIGINT        PRIMARY KEY AUTO_INCREMENT,

  -- 层级定位字段
  top_product_code           VARCHAR(32)   NOT NULL COMMENT '顶层产品料号',
  parent_code                VARCHAR(32)   NOT NULL COMMENT '直接父节点 顶层时等于自己',
  material_code              VARCHAR(32)   NOT NULL COMMENT '当前节点料号',
  level                      INT           NOT NULL COMMENT '层级 顶层=0',
  path                       VARCHAR(512)  NOT NULL COMMENT '/top/.../material/',
  sort_seq                   INT           NULL     COMMENT 'U9 子件项次',

  -- 用量双口径（直接父 / 累计到顶层）
  qty_per_parent             DECIMAL(20,8) NULL     COMMENT '相对直接父用量',
  qty_per_top                DECIMAL(20,8) NULL     COMMENT '累计到顶层用量',

  -- 业务属性（从 u9_source 直接映射）
  material_name              VARCHAR(128)  NULL     COMMENT '料品品名',
  material_spec              VARCHAR(256)  NULL     COMMENT '规格',
  shape_attr                 VARCHAR(32)   NULL     COMMENT '形态属性',
  source_category            VARCHAR(32)   NULL     COMMENT '生产分类 制造件/采购件/半成品',
  cost_element_code          VARCHAR(32)   NULL     COMMENT '成本要素编码',
  bom_purpose                VARCHAR(16)   NULL     COMMENT '仅 U9 有 手工/电子图库留 NULL',
  bom_version                VARCHAR(16)   NULL     COMMENT 'BOM 版本号',
  bom_status                 VARCHAR(16)   NULL     COMMENT 'BOM 状态',
  u9_is_cost_flag            TINYINT(1)    NULL     COMMENT '参考不权威',
  is_leaf                    TINYINT(1)    NULL     COMMENT '无子件则为 1',

  -- 时效：U9 原样继承，支持多版本并存
  effective_from             DATE          NULL     COMMENT 'BOM 版本起始日期',
  effective_to               DATE          NULL     COMMENT 'BOM 版本结束日期 NULL 或 9999-12-31 = 当前生效',

  -- 数据源标识
  source_type                VARCHAR(16)   NOT NULL DEFAULT 'U9' COMMENT 'U9 / MANUAL / E_DRAWING 本期只有 U9',

  -- 批次追溯
  source_import_batch_id     VARCHAR(64)   NOT NULL COMMENT '来自 u9_source 批次',
  build_batch_id             VARCHAR(64)   NOT NULL COMMENT '本次层级构建批次',
  built_at                   DATETIME      NOT NULL COMMENT '层级构建时间',

  -- 业务单元隔离（沿用 V21）
  business_unit_type         VARCHAR(16)   NULL     COMMENT 'COMMERCIAL / HOUSEHOLD',

  -- 唯一键：同一 顶层+源+目的+生效日期 下 (料号, 直接父) 不能重复
  UNIQUE KEY uk_node           (top_product_code, source_type, bom_purpose, effective_from, material_code, parent_code),
  INDEX idx_top_path           (top_product_code, path),
  INDEX idx_top_parent         (top_product_code, parent_code),
  INDEX idx_top_material       (top_product_code, material_code),
  INDEX idx_build_batch        (build_batch_id),
  INDEX idx_bu_purpose         (business_unit_type, bom_purpose),
  INDEX idx_effective          (effective_from, effective_to)
) COMMENT='BOM 事实层 DWD：U9 单层父子 + 派生 path/level/qty_per_top 版本 append-only';


-- ---------------------------------------------------------------------------
-- 第 3 层 · DWS 业务视图层：拍平后的结算行，新表替代老表 lp_bom_manage_item
--                           带版本锁定，绑定 OA + asOfDate
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS lp_bom_costing_row (
  id                          BIGINT        PRIMARY KEY AUTO_INCREMENT,

  -- 关联 OA 报价单
  oa_no                       VARCHAR(64)   NOT NULL COMMENT '所属 OA 单号',

  -- 结构定位
  top_product_code            VARCHAR(32)   NOT NULL COMMENT '顶层产品料号',
  parent_code                 VARCHAR(32)   NULL     COMMENT '直接父节点 顶层时为 NULL',
  material_code               VARCHAR(32)   NOT NULL COMMENT '当前结算行料号',
  level                       INT           NOT NULL COMMENT '层级 顶层=0',
  path                        VARCHAR(512)  NOT NULL COMMENT '/top/.../material/',

  -- 用量
  qty_per_parent              DECIMAL(20,8) NULL     COMMENT '相对直接父用量',
  qty_per_top                 DECIMAL(20,8) NOT NULL COMMENT '累计到顶层用量',

  -- 结算行标记
  is_costing_row              TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '是否结算行',
  subtree_cost_required       TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '下游是否走子树算法',

  -- 追溯：指回 raw_hierarchy 节点 + 命中的过滤规则
  raw_hierarchy_node_id       BIGINT        NULL     COMMENT '指向 lp_bom_raw_hierarchy.id',
  matched_drill_rule_id       BIGINT        NULL     COMMENT '命中的过滤规则 id',

  -- 业务属性（展示用，拍平时从 raw_hierarchy 拷贝）
  material_name               VARCHAR(128)  NULL     COMMENT '料品品名',
  material_spec               VARCHAR(256)  NULL     COMMENT '规格',
  shape_attr                  VARCHAR(32)   NULL     COMMENT '形态属性',
  source_category             VARCHAR(32)   NULL     COMMENT '生产分类',
  cost_element_code           VARCHAR(32)   NULL     COMMENT '成本要素编码',
  bom_purpose                 VARCHAR(16)   NULL     COMMENT 'BOM 生产目的',
  bom_version                 VARCHAR(16)   NULL     COMMENT 'BOM 版本号',
  u9_is_cost_flag             TINYINT(1)    NULL     COMMENT '参考不权威',
  effective_from              DATE          NULL     COMMENT 'BOM 版本起始日期',
  effective_to                DATE          NULL     COMMENT 'BOM 版本结束日期',

  -- 批次追溯
  build_batch_id              VARCHAR(64)   NOT NULL COMMENT '拍平批次 ID',
  built_at                    DATETIME      NOT NULL COMMENT '拍平时间',

  -- 版本锁定（锁月核心）
  as_of_date                  DATE          NOT NULL COMMENT '本次拍平使用的 BOM 版本基准日期',
  raw_version_effective_from  DATE          NOT NULL COMMENT '冻住 raw_hierarchy 行的 effective_from',

  -- 业务单元隔离
  business_unit_type          VARCHAR(16)   NULL     COMMENT 'COMMERCIAL / HOUSEHOLD',

  -- 审计
  created_at                  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at                  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

  -- 唯一键：同一 OA + 顶层 + 料号 + 版本基准 下不能有重复行
  UNIQUE KEY uk_oa_material_version (oa_no, top_product_code, material_code, as_of_date, raw_version_effective_from),
  INDEX idx_oa_product        (oa_no, top_product_code),
  INDEX idx_oa_path           (oa_no, path),
  INDEX idx_raw_node          (raw_hierarchy_node_id),
  INDEX idx_subtree_required  (subtree_cost_required),
  INDEX idx_build_batch       (build_batch_id),
  INDEX idx_material          (material_code),
  INDEX idx_bu_purpose        (business_unit_type, bom_purpose)
) COMMENT='BOM 结算行（拍平后的业务视图层），替代 lp_bom_manage_item';


-- ---------------------------------------------------------------------------
-- 过滤规则表：拍平阶段（阶段 C）按 match_type/match_value 命中后打标
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS bom_stop_drill_rule (
  id                          BIGINT        PRIMARY KEY AUTO_INCREMENT,

  -- 匹配条件
  match_type                  VARCHAR(32)   NOT NULL COMMENT 'NAME_LIKE / MATERIAL_CODE_PREFIX / MATERIAL_TYPE / CATEGORY_EQ / SHAPE_ATTR_EQ',
  match_value                 VARCHAR(128)  NOT NULL COMMENT '匹配值',

  -- 动作
  drill_action                VARCHAR(32)   NOT NULL DEFAULT 'STOP_AND_COST_ROW' COMMENT 'STOP_AND_COST_ROW / EXCLUDE / REPLACE',
  mark_subtree_cost_required  TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '命中后是否打 subtree_cost_required=1',
  replace_to_code             VARCHAR(32)   NULL     COMMENT 'REPLACE 动作时的目标料号',

  -- 优先级 / 时效
  priority                    INT           NOT NULL DEFAULT 100 COMMENT '数字越小优先级越高',
  enabled                     TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '是否启用',
  effective_from              DATE          NULL     COMMENT '规则生效起',
  effective_to                DATE          NULL     COMMENT '规则生效止',
  business_unit_type          VARCHAR(16)   NULL     COMMENT 'NULL=全局 COMMERCIAL/HOUSEHOLD',

  -- 审计
  remark                      VARCHAR(255)  NULL     COMMENT '规则说明',
  created_by                  VARCHAR(64)   NULL     COMMENT '创建人',
  created_at                  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_by                  VARCHAR(64)   NULL     COMMENT '更新人',
  updated_at                  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted                     TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '软删标记',

  INDEX idx_match    (match_type, match_value),
  INDEX idx_enabled  (enabled, priority),
  INDEX idx_bu       (business_unit_type)
) COMMENT='BOM 过滤规则 拍平阶段应用';


-- ---------------------------------------------------------------------------
-- 初始规则数据（幂等：WHERE NOT EXISTS 防重，脚本重复执行不会多次插入）
--   规则 1：NAME_LIKE '接管' → STOP_AND_COST_ROW + 标子树算法
--   规则 2 原本是 SHAPE_ATTR_EQ '部品联动'，业务复核后认定是误导性规则
--     取价实际以叶子料号为单位，不存在"部品联动"这条独立路由，故种子已移除
-- ---------------------------------------------------------------------------
INSERT INTO bom_stop_drill_rule
  (match_type, match_value, drill_action, mark_subtree_cost_required, priority, remark)
SELECT 'NAME_LIKE', '接管', 'STOP_AND_COST_ROW', 1, 10, '接管类物料不下钻 下游走子树算法'
WHERE NOT EXISTS (
  SELECT 1 FROM bom_stop_drill_rule
   WHERE match_type = 'NAME_LIKE' AND match_value = '接管'
);

-- V40 结束
