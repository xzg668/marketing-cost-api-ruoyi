-- 新费用问题不可沿用旧新品答案；只重置未提交草稿的完成标志，保留内容和全部审批历史。
UPDATE lp_quote_tech_module m
JOIN lp_quote_tech_product p ON p.id=m.product_id AND p.current_edit_version_id=m.current_version_id
JOIN lp_quote_tech_data_version v ON v.id=m.current_version_id AND v.product_id=p.id
SET m.module_status='EDITING',m.last_validation_code='PROFILE_FEES_REQUIRED',
    m.last_validation_message='请确认是否含新增工装模具认证费并核对三项单件费用',
    m.row_version=m.row_version+1,m.updated_at=NOW(),p.row_version=p.row_version+1,p.updated_at=NOW()
WHERE p.active_flag=1 AND p.content_schema_version=2 AND m.module_type='PROFILE'
  AND m.module_status='READY' AND v.version_status='DRAFT'
  AND COALESCE(JSON_TYPE(JSON_EXTRACT(v.product_fees_json,'$.includesNewToolingMouldCertificationFee')),'NULL')<>'BOOLEAN';
