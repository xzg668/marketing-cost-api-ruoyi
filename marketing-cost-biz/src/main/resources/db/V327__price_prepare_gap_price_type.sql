-- 缺口按实际尝试的取价类型展示；历史未知类型保持 NULL，不猜测报价依据。
ALTER TABLE lp_price_prepare_gap
  ADD COLUMN price_type VARCHAR(32) NULL COMMENT '缺口对应的已尝试价格类型；无路由或混合原因时为空';
