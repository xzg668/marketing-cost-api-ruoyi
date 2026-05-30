-- V135: 隐藏 demo 残留的辅料管理和工资表菜单
--
-- 背景：
--   当前成本核算口径不再由“辅料管理 / 工资表”页面维护驱动。
--   这些入口继续展示会误导用户，以为修改这些 demo 页面会影响普通报价或月度调价。
--
-- 策略：
--   1. 不删除 sys_menu / sys_role_menu，避免历史环境、权限配置和后端接口引用断裂。
--   2. 将历史 V5 菜单和当前 40159 基础数据下的菜单统一设为隐藏 + 停用。
--   3. 前端 Sidebar 另有 ID 兜底过滤，保证迁移未及时执行的环境也不展示。

SET NAMES utf8mb4;

UPDATE sys_menu
SET visible = '1',
    status = '1',
    update_by = 'system',
    update_time = NOW(),
    remark = CASE
      WHEN remark IS NULL OR remark = '' THEN 'V135：demo 残留菜单，已隐藏停用'
      WHEN remark LIKE '%V135：demo 残留菜单，已隐藏停用%' THEN remark
      ELSE CONCAT(remark, '；V135：demo 残留菜单，已隐藏停用')
    END
WHERE menu_id IN (
  -- V5 历史基础数据菜单
  305, 3051, 3052, 307,
  -- 当前 40159 基础数据菜单
  40164, 40176, 40182, 40183
)
   OR (
     menu_name IN ('辅料管理', '辅料价格表', '辅料上浮比率表', '工资表')
     AND (
       path IN ('aux', 'subject', 'item', 'salary',
                '/base/aux', '/base/aux/subject', '/base/aux/item', '/base/salary')
       OR component IN ('base/auxiliary/subject/index', 'base/auxiliary/item/index', 'base/salary/index')
       OR component IS NULL
     )
   );
