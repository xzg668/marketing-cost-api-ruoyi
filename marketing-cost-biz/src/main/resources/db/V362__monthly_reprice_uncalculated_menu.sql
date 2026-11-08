-- Menu data only; apply only in the authorized isolation environment until deployment is requested.
-- Reuse the existing monthly readers. Operation authority remains ADMIN/BU_DIRECTOR in the API.
SET @mrp_uncalculated_parent=(SELECT parent_id FROM sys_menu
  WHERE component='pages:MonthlyRepricePage' AND path='/cost/monthly-reprice' ORDER BY menu_id LIMIT 1);
UPDATE sys_menu SET menu_name='再核算与调账',update_time=NOW()
  WHERE menu_id=@mrp_uncalculated_parent AND menu_type='M';
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '未核算明细',@mrp_uncalculated_parent,2,'/cost/uncalculated','pages:MonthlyRepriceUncalculatedPage',
  'C','0','0','price:monthly-reprice:list','list','monthly-reprice',NOW()
WHERE @mrp_uncalculated_parent IS NOT NULL
  AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE component='pages:MonthlyRepriceUncalculatedPage');
INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
SELECT DISTINCT grant_row.role_id,new_entry.menu_id FROM sys_role_menu grant_row
JOIN sys_menu old_entry ON old_entry.menu_id=grant_row.menu_id
JOIN sys_menu new_entry ON new_entry.component='pages:MonthlyRepriceUncalculatedPage'
WHERE old_entry.component='pages:MonthlyRepricePage' AND old_entry.path='/cost/monthly-reprice';
