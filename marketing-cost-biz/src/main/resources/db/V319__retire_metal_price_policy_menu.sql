-- 非铜因素统一按财务发布取值；退休可独立切换客户锌/铝价的旧设置页。
-- 保留历史菜单和映射数据，冻结差额仍可读取历史因素身份。
UPDATE sys_menu SET status='1', visible='1'
WHERE component='price/linked/quote-base-mapping/index';
