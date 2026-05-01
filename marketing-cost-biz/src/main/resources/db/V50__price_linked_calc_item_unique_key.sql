-- =============================================================================
-- V50  联动价 calc_item 加唯一键 + 清重复                                  2026-04-28
--
-- 强制 connection charset 为 utf8mb4
SET NAMES utf8mb4;
-- =============================================================================
--
-- 业务背景：
--   lp_price_linked_calc_item 之前没有 UNIQUE KEY，全靠应用层 buildKey 内存去重。
--   实际暴露问题：buildKey 之前用了 (oa, item, shape) 作为业务键，但 shape_attr 在
--   不同 BOM 模型下值不一样（V21 旧模型 shape='部品联动'，新模型 shape='采购件'），
--   导致同一 OA+料号在 calc_item 表里出现两份，每次刷新如果 shape 变化就再生一条。
--
--   修法：
--     1) 应用层 buildKey 改成 (oa, item) 为业务键（已在 PriceLinkedCalcServiceImpl 改完）
--     2) DB 加 UK 防御：(oa_no, item_code, business_unit_type) 三元唯一
--     3) 清理已有重复行（DELETE 已在线 SQL 跑过；本脚本含 IGNORE 兜底，重跑安全）
--
-- 幂等：
--   - 重复清理：保留每组 (oa, item, BU) 中 id 最大的一条（最新算价）
--   - UK 创建：检查不存在再加（避免重复跑报错）
-- =============================================================================

-- 1) 兜底清重复（应用层 + UK 都缺位时，可能再次出现）
DELETE FROM lp_price_linked_calc_item
WHERE id IN (
  SELECT id FROM (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY oa_no, item_code, business_unit_type
                              ORDER BY id DESC) AS rn
    FROM lp_price_linked_calc_item
  ) t WHERE t.rn > 1
);

-- 2) 加 UNIQUE KEY（先检查不存在再加）
DROP PROCEDURE IF EXISTS v50_add_uk;
DELIMITER $$
CREATE PROCEDURE v50_add_uk()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                 WHERE table_schema=DATABASE()
                   AND table_name='lp_price_linked_calc_item'
                   AND index_name='uk_pl_calc_oa_item_bu') THEN
    ALTER TABLE lp_price_linked_calc_item
      ADD UNIQUE KEY uk_pl_calc_oa_item_bu (oa_no, item_code, business_unit_type);
  END IF;
END$$
DELIMITER ;

CALL v50_add_uk();
DROP PROCEDURE v50_add_uk;
