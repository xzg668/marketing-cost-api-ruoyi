-- 类型对照只保留查询及完整顺序编辑，移除已退役的新增/导入/删除按钮权限。
START TRANSACTION;
DELETE rm FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id=rm.menu_id
WHERE m.perms IN ('base:material-price-type:add','base:material-price-type:import','base:material-price-type:remove')
  AND m.menu_type='F';
DELETE FROM sys_menu
WHERE perms IN ('base:material-price-type:add','base:material-price-type:import','base:material-price-type:remove')
  AND menu_type='F';
COMMIT;
