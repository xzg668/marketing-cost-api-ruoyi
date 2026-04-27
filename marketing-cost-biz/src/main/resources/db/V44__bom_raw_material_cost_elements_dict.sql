-- =============================================================================
-- V44  T11 增强 · BOM 原材料成本要素白名单字典                  2026-04-27
--
-- 强制 connection charset 为 utf8mb4，防止中文 dict_label 入库乱码
-- （docker exec -i 默认 stdin charset=latin1，不加这行字典中文会乱）
SET NAMES utf8mb4;
-- =============================================================================
--
-- 本脚本职责：
--   新增字典 bom_raw_material_cost_elements，作为 LEAF_ROLLUP_TO_PARENT 命中的
--   "前置硬条件"：叶子的 cost_element_code 必须在此白名单内，才允许进入字典
--   bom_leaf_rollup_codes 的料号 / 名称匹配。
--
-- 业务背景：
--   原算法只看叶子的 material_category_1 编码 / material_name 关键词，可能误把
--   "名字虽然含拉制铜管但 cost_element_code 不是原材料"的节点上卷。新规则收紧
--   为 3 路与：cost_element_code ∈ 原材料字典  AND  (cat1 命中 OR name 命中)。
--
-- 初始种子：
--   No101 = 主要材料-原材料（U9 标准）—— 拉制铜管 / 紫铜板 等真原材料叶子全是 No101
--
-- 业务后续可在 /system/dict 自助 INSERT 扩展：
--   - 若以后 No311 / 自定义编码也要算"原材料"，加一条即可，不需改代码
--
-- 幂等：所有 INSERT 走 WHERE NOT EXISTS，重跑无副作用
-- =============================================================================

-- 1) 字典类型
INSERT INTO sys_dict_type (dict_name, dict_type, status, remark)
  SELECT 'BOM 原材料成本要素白名单', 'bom_raw_material_cost_elements', '0',
         'T11 增强：LEAF_ROLLUP_TO_PARENT 前置硬条件——叶子 cost_element_code 必须在此白名单内才允许上卷'
  FROM DUAL
  WHERE NOT EXISTS (
    SELECT 1 FROM sys_dict_type WHERE dict_type = 'bom_raw_material_cost_elements'
  );

-- 兜住 V44 第一次 apply 时 connection charset 不是 utf8mb4 导致 dict_name 入库乱码的旧环境
-- 幂等：dict_name 已正确的环境此 UPDATE 影响 0 行
UPDATE sys_dict_type
   SET dict_name = 'BOM 原材料成本要素白名单'
 WHERE dict_type = 'bom_raw_material_cost_elements'
   AND dict_name <> 'BOM 原材料成本要素白名单';

-- 2) 种子条目（业务后续 /system/dict 自助加）
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 1, '主要材料-原材料', 'No101', 'bom_raw_material_cost_elements', '0',
         'U9 标准 cost_element_code = No101 (拉制铜管 / 紫铜板 等真原材料叶子全是 No101)'
  FROM DUAL
  WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data
                    WHERE dict_type='bom_raw_material_cost_elements' AND dict_value='No101');

-- 注意：故意不种 No102 (制造件) / No103 (辅助材料) / No104 (包装材料)
--   业务侧 2026-04-27 明确：先只放 No101；以后哪些 cost_element 也算"原材料"由业务在
--   /system/dict 自助加，不动算法

-- V44 结束
