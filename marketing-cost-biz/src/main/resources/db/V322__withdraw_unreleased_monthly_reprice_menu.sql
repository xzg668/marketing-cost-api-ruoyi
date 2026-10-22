-- 11月上线范围暂不开放月度调价，只撤下菜单及动态路由，不删除业务数据。
-- 隐藏的“价格准备”及其按钮权限仍供正常报价使用，不随目录停用。
UPDATE sys_menu
SET status='1', visible='1', update_by='release-menu', update_time=NOW()
WHERE (menu_type='M' AND menu_name='核算与结账' AND parent_id=0)
   OR (menu_type='C' AND (path='/settlement/monthly-adjustment'
       OR component IN ('settlement/monthly-adjustment/index','pages:MonthlyRepricePage')));
