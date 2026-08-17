-- BOM 单层源表与完整层级表由 EasyData 计算并推送，报价系统只消费。
-- 禁用历史“U9 BOM 原始数据”上传/构建入口，保留“BOM 层级树查看”只读入口。
SET NAMES utf8mb4;

UPDATE sys_menu
   SET visible = '1',
       status = '1',
       update_time = NOW(),
       remark = 'V215：EasyData 负责 BOM 推送；报价系统已移除 Excel 导入和 Java 层级构建'
 WHERE menu_id IN (202, 40167)
    OR perms = 'bom-data:u9-raw:list';
