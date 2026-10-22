-- 旧公式配置页只保存浏览器内存模拟数据。正式公式与手工绑定在联动价明细维护。
-- 保留历史菜单及授权记录，仅停用失效入口；物料表仍使用真实 MaterialMasterPage。
UPDATE sys_menu SET status='1', visible='1'
WHERE component IN ('price/linked/formula/index','pages:PriceLinkedFormulaPage');
