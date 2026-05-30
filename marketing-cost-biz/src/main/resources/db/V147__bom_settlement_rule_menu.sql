-- V147: BOM 结算规则菜单替换旧“BOM 明细过滤规则”入口
-- 只更新菜单语义和组件指向；规则表清理由后续迁移负责。

UPDATE sys_menu
   SET menu_name = 'BOM 结算规则',
       parent_id = 40461,
       order_num = 2,
       path = '/bom-data/settlement-rules',
       component = 'pages:BomSettlementRulePage',
       menu_type = 'C',
       visible = '0',
       status = '0',
       perms = 'bom-data:settlement-rule:list',
       icon = '#',
       update_time = NOW(),
       remark = 'BOM 树节点结算规则配置，读写 lp_bom_settlement_rule'
 WHERE menu_id IN (301, 40171);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role_id, 301
  FROM sys_role_menu
 WHERE menu_id = 40461;
