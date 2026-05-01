-- =============================================================================
-- V45  价格管理菜单简化：保留 固定价 / 联动价 / 区间价 三种   2026-04-27
--
-- 强制 connection charset 为 utf8mb4，防止 menu_name 中文乱码
SET NAMES utf8mb4;
-- =============================================================================
--
-- 业务背景（2026-04-27 业务确认）：
--   取价的本质只有 3 桶：固定价 / 联动价 / 区间价。
--   "结算价" 实际是固定基价 + 联动结果价两种口径的容器（业务批次快照），
--   不是独立价格类型；"委外结算价表 / 委外固定价表" 是 placeholder 占位菜单
--   （前端是 PlaceholderPage 假数据，数据库表本身不存在）。
--
-- 本脚本职责：
--   1) 级联清 sys_role_menu 中对要删菜单的引用（避免角色挂孤儿 menu_id）
--   2) DELETE 3 个价格类菜单 + 1 个 perms 子菜单
--   3) 重排剩余 3 个：固定价=1 / 联动价=2 / 区间价=3
--
-- 不动：
--   - 后端 PriceSettleController / PriceSettleItem 等 193 处代码（取价路由可能依赖）
--   - lp_price_settle / lp_price_settle_item 表（含历史结算单数据）
--   - 前端 PriceFixedPage / PriceLinked* / PriceRangePage / 老结算价 vue 文件
--
-- 锚定：用 path / perms（不用 menu_id），避免不同环境 id 漂移
-- 幂等：不存在的菜单 DELETE 影响 0 行；UPDATE 已正确的 order_num 不变也无副作用
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1) 级联清 sys_role_menu —— 用子查询锁定要删的 menu_id
-- -----------------------------------------------------------------------------
DELETE FROM sys_role_menu
WHERE menu_id IN (
  SELECT menu_id FROM sys_menu
  WHERE path IN ('/price/settle', '/price/outsource_settle', '/price/outsource_fixed')
     OR perms = 'price:settle:list'
);

-- -----------------------------------------------------------------------------
-- 2) DELETE sys_menu —— 4 条（3 个目录菜单 + 1 个 perms 子菜单）
-- -----------------------------------------------------------------------------
DELETE FROM sys_menu
WHERE path IN ('/price/settle', '/price/outsource_settle', '/price/outsource_fixed')
   OR perms = 'price:settle:list';

-- -----------------------------------------------------------------------------
-- 3) 重排剩余 3 个价格菜单的 order_num
-- -----------------------------------------------------------------------------
UPDATE sys_menu SET order_num = 1 WHERE path = '/price/fixed';
UPDATE sys_menu SET order_num = 2 WHERE path = '/price/linked';
UPDATE sys_menu SET order_num = 3 WHERE path = '/price/range';

-- V45 结束
