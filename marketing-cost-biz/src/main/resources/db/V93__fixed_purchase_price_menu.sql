-- =============================================================================
-- V93  固定价菜单改名为固定采购价                                      2026-05-18
--
SET NAMES utf8mb4;
-- =============================================================================
--
-- 业务背景：
--   固定采购价与结算固定价共用 lp_price_fixed_item，但前端菜单必须拆开。
--   FPT-04 先把原“固定价”页面收敛为“固定采购价”，仍复用原 path/component/perms。
--   结算固定价菜单由 FPT-05 新增。
-- =============================================================================

UPDATE sys_menu
SET menu_name = '固定采购价',
    remark = '固定采购价：导入固定采购价5，source_type=PURCHASE_FIXED',
    update_time = NOW()
WHERE path IN ('fixed', '/price/fixed')
   OR component = 'price/fixed/index'
   OR perms = 'price:fixed:list';

UPDATE sys_menu
SET menu_name = '固定采购价 查询',
    update_time = NOW()
WHERE perms = 'price:fixed:list';

-- V93 结束
