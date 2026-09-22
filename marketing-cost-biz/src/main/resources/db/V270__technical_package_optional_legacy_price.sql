-- 新包装表单只维护母子关系与用量，价格由公共取价／价格补录处理。
-- 历史四模块版本仍使用原价格字段校验冻结内容，暂不删除或改写历史数据。
ALTER TABLE lp_quote_tech_package_item
    MODIFY COLUMN price_basis_type VARCHAR(64) NULL COMMENT '旧版价格依据；新包装补录留空';
