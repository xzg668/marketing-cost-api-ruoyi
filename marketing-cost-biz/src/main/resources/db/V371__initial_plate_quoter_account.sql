-- 用户明确指定首个板换报价员。仅初始化既有账号，不创建账号或覆盖已有配置。
INSERT INTO lp_quote_visibility_account(user_id,quote_scope,created_by,updated_by)
SELECT u.user_id,'PLATE','INITIAL_CONFIGURATION','INITIAL_CONFIGURATION'
FROM sys_user u WHERE BINARY u.user_name=BINARY '胡芳玉' AND u.del_flag='0'
AND NOT EXISTS(SELECT 1 FROM lp_quote_visibility_account q WHERE q.user_id=u.user_id);
