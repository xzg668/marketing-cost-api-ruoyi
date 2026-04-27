-- =================================================================
-- V32: 修正 V31 初版 FINANCE 回填时的字符集乱码
--
-- 症状：/items/{id}/trace 对 Cu / Zn 返回 0；查 lp_price_variable 看到
--       resolver_params.priceSource 显示为 "å¹³å‡ä»·"（实为 "平均价" 的
--       UTF-8 bytes 被按 Latin-1 误读再以 utf8mb4 存盘的"双重编码"）。
--
-- 根因：容器 new_mysql 的 character_set_client/connection 默认 latin1；
--       首次执行 V31 时客户端未指定 --default-character-set=utf8mb4，
--       UPDATE 中硬编码的中文字面量（'平均价' / '美国柜装黄铜'）在
--       协议层被 server 按 latin1 解读，再以 utf8mb4 存入 JSON 列。
--
-- 做法：以 utf8mb4 连接重跑 3 条 FINANCE UPDATE，把 priceSource/shortName
--       写成干净的中文。ENTITY/DERIVED/FORMULA/CONST 是从既有 JSON 列
--       原样复制，未受影响，无需修。
--
-- 重放指令（必须指定 charset）：
--   docker exec -i new_mysql mysql --default-character-set=utf8mb4 \
--     -uroot -proot123 marketing_cost \
--     < V32__fix_finance_resolver_params_charset.sql
-- =================================================================

SET NAMES utf8mb4;

UPDATE `lp_price_variable` SET
    resolver_params = JSON_OBJECT(
        'factorCode',  'Cu',
        'priceSource', '平均价',
        'buScoped',    TRUE)
  WHERE variable_code = 'Cu' AND factor_type = 'FINANCE_FACTOR';

UPDATE `lp_price_variable` SET
    resolver_params = JSON_OBJECT(
        'factorCode',  'Zn',
        'priceSource', '平均价',
        'buScoped',    TRUE)
  WHERE variable_code = 'Zn' AND factor_type = 'FINANCE_FACTOR';

UPDATE `lp_price_variable` SET
    resolver_params = JSON_OBJECT(
        'shortName',   '美国柜装黄铜',
        'priceSource', '平均价',
        'buScoped',    TRUE)
  WHERE variable_code = 'us_brass_price' AND factor_type = 'FINANCE_FACTOR';

-- 人工校验（拷到客户端跑）：
-- SELECT variable_code, CAST(resolver_params AS CHAR CHARACTER SET utf8mb4)
--   FROM lp_price_variable
--  WHERE variable_code IN ('Cu','Zn','us_brass_price');
