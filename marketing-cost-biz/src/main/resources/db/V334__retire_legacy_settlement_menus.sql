-- 只收口旧入口，保留菜单、授权及历史价格行；新维护权限由V333独立配置。
UPDATE sys_menu entry LEFT JOIN sys_menu parent ON parent.menu_id = entry.parent_id
SET entry.status='1', entry.visible='1', entry.update_by='system', entry.update_time=NOW()
WHERE (entry.component IN ('price/settle/index','price/settle-fixed/index','pages:PriceSettlePage','pages:PriceSettleFixedPage')
   OR parent.component IN ('price/settle/index','price/settle-fixed/index','pages:PriceSettlePage','pages:PriceSettleFixedPage')
   OR entry.perms LIKE 'price:settle:%')
  AND (entry.status <> '1' OR entry.visible <> '1');
