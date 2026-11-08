-- Menu data only. Prepare/review here; apply only in an explicitly authorized environment.
-- Keep existing entry identities, granted readers and all historical business rows.
SET @mrp1130_entry = (
  SELECT menu_id FROM sys_menu
  WHERE component IN ('settlement/monthly-adjustment/index','pages:MonthlyRepricePage')
  ORDER BY CASE WHEN menu_id=40197 THEN 0 ELSE 1 END,menu_id LIMIT 1
);
SET @mrp1130_parent = (SELECT menu_id FROM sys_menu WHERE menu_type='M' AND parent_id=0 AND path='cost' ORDER BY CASE WHEN menu_id=40161 THEN 0 ELSE 1 END,menu_id LIMIT 1);
UPDATE sys_menu SET menu_name='核算与调价',visible='0',status='0',update_time=NOW()
WHERE menu_id=@mrp1130_parent;
UPDATE sys_menu entry JOIN sys_menu parent ON parent.menu_id=@mrp1130_parent
SET entry.menu_name='月度调价',entry.parent_id=parent.menu_id,entry.path='/cost/monthly-reprice',
    entry.component='pages:MonthlyRepricePage',entry.perms='price:monthly-reprice:list',
    entry.visible='0',entry.status='0',entry.update_time=NOW()
WHERE entry.menu_id=@mrp1130_entry;
UPDATE sys_menu SET visible='1',status='1',update_time=NOW()
WHERE component IN ('settlement/monthly-adjustment/index','pages:MonthlyRepricePage')
  AND menu_id<>@mrp1130_entry;
INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
SELECT DISTINCT grant_row.role_id,entry.parent_id FROM sys_role_menu grant_row
JOIN sys_menu entry ON entry.menu_id=grant_row.menu_id
WHERE entry.menu_id=@mrp1130_entry;
