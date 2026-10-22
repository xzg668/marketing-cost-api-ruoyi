-- 结算联动统一引用商用财务发布。保留历史基价数据及角色关联，只撤销独立维护入口。
UPDATE sys_menu
SET status='1', visible='1', update_by='system', update_time=NOW()
WHERE perms IN ('price:settlement-base:list','price:settlement-base:edit')
  AND (status<>'1' OR visible<>'1');
