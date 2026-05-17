-- =============================================================================
-- V61: 报价单接入菜单收敛
-- -----------------------------------------------------------------------------
-- 背景：
--   V60 新增了顶级“数据接入”(menu_id=200)，但历史库中可能已经存在一个旧的
--   顶级“数据接入”目录，承载 U9 BOM、电子图库 BOM、BOM 明细录入等旧入口。
--   若两个目录同名，会在侧边栏出现两个“数据接入”，影响上线使用。
--
-- 处理：
--   1. menu_id=200 保持为新的报价单接入目录。
--   2. 其他顶级旧“数据接入”目录改名为“BOM 数据管理”，路径改为 bom-data。
--   3. 旧 OA 报价单入口由新“报价单接入”替代，菜单隐藏。
-- =============================================================================

SET NAMES utf8mb4;

UPDATE sys_menu
   SET menu_name = 'BOM 数据管理',
       path = 'bom-data',
       icon = 'tree-table',
       update_time = NOW(),
       remark = CASE
         WHEN remark IS NULL OR remark = '' THEN 'V61：旧数据接入目录改名为 BOM 数据管理，避免与报价单接入目录重名'
         ELSE CONCAT(remark, '；V61：旧数据接入目录改名为 BOM 数据管理')
       END
 WHERE parent_id = 0
   AND menu_id <> 200
   AND menu_name = '数据接入'
   AND path = 'ingest';

UPDATE sys_menu
   SET visible = '1',
       update_time = NOW(),
       remark = CASE
         WHEN remark IS NULL OR remark = '' THEN 'V61：旧 OA 报价单入口由新报价单接入替代，菜单隐藏'
         ELSE CONCAT(remark, '；V61：旧 OA 报价单入口隐藏')
       END
 WHERE menu_type = 'C'
   AND (
     component = 'ingest/oa-form/index'
     OR path IN ('oa-form', '/ingest/oa-form')
     OR menu_name IN ('OA报价单', 'OA 报价单')
   );

-- V61 结束
