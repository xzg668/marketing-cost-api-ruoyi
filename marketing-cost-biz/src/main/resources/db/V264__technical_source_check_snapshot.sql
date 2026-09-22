-- 核算和财务复查共用产品行/月工作区；检查原文与版本用于防止过时分派。
ALTER TABLE lp_quote_costing_workspace
  ADD COLUMN technical_check_json JSON NULL COMMENT '最近一次九模块来源检查',
  ADD COLUMN technical_check_fingerprint CHAR(64) NULL COMMENT '来源身份和事实指纹，不含检查时间';
