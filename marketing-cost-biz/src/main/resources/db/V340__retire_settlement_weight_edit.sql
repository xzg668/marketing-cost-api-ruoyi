-- 重量随结算原稿导入，不再单独维护；保留历史菜单和角色关系供审计。
UPDATE sys_menu
SET status = '1', visible = '1', update_by = 'system', update_time = NOW()
WHERE perms = 'price:settlement:edit'
  AND (status <> '1' OR visible <> '1');
