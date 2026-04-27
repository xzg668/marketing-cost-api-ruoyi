-- =============================================================================
-- V43  T11 · BOM 叶子上卷规则字典 + 老规则停用            2026-04-27
--
-- 强制 connection charset 为 utf8mb4，防止 NAME:中文 被存成乱码
-- （docker exec -i 默认 stdin charset=latin1，不加这行字典中文会乱）
SET NAMES utf8mb4;
-- =============================================================================
--
-- 本脚本职责（设计文档 bom-leaf-rollup-design-2026-04-27.md §5.1 / §10）：
--   1) 新增字典类型 bom_leaf_rollup_codes（叶子上卷分类白名单）
--   2) 写入 5 条种子（1 条 U9 编码 + 4 条 NAME: 名称兜底）
--      —— 90%+ 拉制铜管节点 material_category_1=NULL，必须靠名称兜底
--   3) 停用 T8 规则 #4（match_value=copper-tube-assembly），业务 2026-04-27 确认
--      121191304 实际是普通制造件而非铜管，规则配置就是错的
--
-- 幂等性：
--   - INSERT 全部 WHERE NOT EXISTS，重跑无报错、不重复
--   - UPDATE 命中条件含 enabled=1，重跑第二次影响 0 行
--   - 不在生产存在该规则的环境也安全（影响 0 行）
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1) 字典类型：BOM 叶子上卷分类
-- -----------------------------------------------------------------------------
INSERT INTO sys_dict_type (dict_name, dict_type, status, remark)
  SELECT 'BOM 叶子上卷分类', 'bom_leaf_rollup_codes', '0',
         'T11 新增：识别需要上卷到父结算的材料叶子（编码白名单 + NAME: 名称兜底）'
  FROM DUAL
  WHERE NOT EXISTS (
    SELECT 1 FROM sys_dict_type WHERE dict_type = 'bom_leaf_rollup_codes'
  );

-- 兜住"V43 第一次 apply 时 connection charset 不是 utf8mb4 导致 dict_name 入库乱码"的旧环境
-- 幂等：dict_name 已经是正确中文的环境此 UPDATE 影响 0 行
UPDATE sys_dict_type
   SET dict_name = 'BOM 叶子上卷分类'
 WHERE dict_type = 'bom_leaf_rollup_codes'
   AND dict_name <> 'BOM 叶子上卷分类';

-- -----------------------------------------------------------------------------
-- 2) 5 条种子（业务后续可在 /system/dict 自助 INSERT 扩展，无需改代码）
--    NAME: 前缀的 dict_value 表示按 material_name contains 兜底匹配
-- -----------------------------------------------------------------------------
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 1, '拉制铜管', '171711404', 'bom_leaf_rollup_codes', '0',
         'U9 编码命中（仅覆盖 3 节点；其余 60+ 节点 category 为 NULL 走 NAME 兜底）'
  FROM DUAL
  WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data
                    WHERE dict_type='bom_leaf_rollup_codes' AND dict_value='171711404');

INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 2, '拉制铜管（名称兜底）', 'NAME:拉制铜管', 'bom_leaf_rollup_codes', '0',
         'U9 category 缺失兜底（覆盖 60+ NULL 节点）'
  FROM DUAL
  WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data
                    WHERE dict_type='bom_leaf_rollup_codes' AND dict_value='NAME:拉制铜管');

INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 3, '紫铜盘管（名称兜底）', 'NAME:紫铜盘管', 'bom_leaf_rollup_codes', '0',
         '紫铜盘管类叶子按名称识别'
  FROM DUAL
  WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data
                    WHERE dict_type='bom_leaf_rollup_codes' AND dict_value='NAME:紫铜盘管');

INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 4, '紫铜直管（名称兜底）', 'NAME:紫铜直管', 'bom_leaf_rollup_codes', '0',
         '紫铜直管类叶子按名称识别'
  FROM DUAL
  WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data
                    WHERE dict_type='bom_leaf_rollup_codes' AND dict_value='NAME:紫铜直管');

INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 5, '直管（名称兜底）', 'NAME:直管', 'bom_leaf_rollup_codes', '0',
         '直管 / 直管转接头类叶子按名称识别'
  FROM DUAL
  WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data
                    WHERE dict_type='bom_leaf_rollup_codes' AND dict_value='NAME:直管');

-- 注意：故意不种 'NAME:管' / 'NAME:铜管' 这种过宽关键词，避免误命中
--   "风管 / 软管 / 排气管 / 铜管接头" 等无关节点；业务后续按需在字典里补

-- -----------------------------------------------------------------------------
-- 3) 停用 T8 老规则 #4（COMPOSITE ROLLUP_TO_PARENT，业务 2026-04-27 确认废弃）
--
--    锚点用 match_value（不用 id，避免不同环境 id 漂移）
--    幂等：第二次执行 enabled=1 已不命中，无副作用
-- -----------------------------------------------------------------------------
UPDATE bom_stop_drill_rule
SET enabled = 0,
    remark  = CONCAT(IFNULL(remark, ''), ' [T11停用-业务废弃-2026-04-27]')
WHERE match_value  = 'copper-tube-assembly'
  AND drill_action = 'ROLLUP_TO_PARENT'
  AND deleted      = 0
  AND enabled      = 1;

-- -----------------------------------------------------------------------------
-- 4) 停用 T7 老规则 #1（NAME_LIKE 接管 STOP_AND_COST_ROW，业务 2026-04-27 简化时确认废弃）
--
--    业务真实模型："叶子是铜管 → 上卷父结算" 一条规则就够（即新规则 LEAF_ROLLUP_TO_PARENT）；
--    不再按"父级名字含接管"做整棵子树 STOP。接管父若下面有铜管叶子，会被 LEAF_ROLLUP
--    自然命中作父结算行；若下面无铜管，接管作中间节点正常下钻，子件按默认叶子结算。
-- -----------------------------------------------------------------------------
UPDATE bom_stop_drill_rule
SET enabled = 0,
    remark  = CONCAT(IFNULL(remark, ''), ' [T11简化-业务确认废弃-2026-04-27]')
WHERE match_value  = '接管'
  AND drill_action = 'STOP_AND_COST_ROW'
  AND deleted      = 0
  AND enabled      = 1;

-- V43 结束
