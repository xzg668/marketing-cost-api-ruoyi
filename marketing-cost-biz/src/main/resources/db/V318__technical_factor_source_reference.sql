-- 技术文件中的公共因素只保留身份及原输入，不另建可修改的公共月价。
-- 放宽引用为空，不删除或改写任何既有来源记录。
ALTER TABLE lp_factor_row_ref MODIFY COLUMN factor_monthly_price_id BIGINT NULL
  COMMENT '私有因素月价；公共因素仅绑定身份，此处为空';
