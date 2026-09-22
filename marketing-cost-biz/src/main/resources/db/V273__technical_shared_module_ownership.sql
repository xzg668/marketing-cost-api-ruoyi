-- 同一产品同一模块跨报价只保留一个办理来源；原任务、草稿和审批版本继续独立追溯。
-- 不以报价月份、人员或组织生成另一份补录。组织等适用条件在读取共享资料时核验。
CREATE TABLE IF NOT EXISTS lp_quote_tech_shared_module (
  product_identity VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  module_type VARCHAR(32) NOT NULL,
  owner_module_id BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (product_identity, module_type),
  UNIQUE KEY uk_tech_shared_owner (owner_module_id),
  CONSTRAINT fk_tech_shared_owner FOREIGN KEY (owner_module_id) REFERENCES lp_quote_tech_module(id),
  CONSTRAINT ck_tech_shared_module CHECK (module_type IN
    ('PROFILE','DRAWING_BOM','MANUFACTURING','PACKAGE','AUXILIARY','SOLDER','SALARY','NET_LOSS'))
) ENGINE=InnoDB COMMENT='产品模块唯一办理来源，价格按料号另行接入';

-- 只登记无歧义的既有来源，不任取最新审批覆盖历史重复资料。
-- 有重复来源的产品由运行时明确报错；本迁移不删除、合并或改写既有资料。
INSERT INTO lp_quote_tech_shared_module(product_identity,module_type,owner_module_id)
SELECT CASE WHEN NULLIF(TRIM(p.material_no),'') IS NULL THEN CONCAT('QUOTE_LINE:',p.oa_form_item_id)
            ELSE CONCAT('MATERIAL:',TRIM(p.material_no)) END,
       m.module_type, MIN(m.id)
FROM lp_quote_tech_module m
JOIN lp_quote_tech_product p ON p.id=m.product_id
JOIN lp_quote_tech_task t ON t.id=p.task_id
WHERE p.content_schema_version=2 AND t.task_status<>'CANCELLED'
  AND m.module_type<>'PRICE' AND (m.assignee_user_id IS NOT NULL OR m.current_version_id IS NOT NULL)
GROUP BY CASE WHEN NULLIF(TRIM(p.material_no),'') IS NULL THEN CONCAT('QUOTE_LINE:',p.oa_form_item_id)
              ELSE CONCAT('MATERIAL:',TRIM(p.material_no)) END, m.module_type
HAVING COUNT(*)=1
ON DUPLICATE KEY UPDATE owner_module_id=lp_quote_tech_shared_module.owner_module_id;
