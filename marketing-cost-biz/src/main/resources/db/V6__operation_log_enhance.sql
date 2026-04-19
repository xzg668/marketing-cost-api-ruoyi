-- V6: 操作日志表增强字段（T30）
-- 新增：business_unit_type / target_id / before_data / after_data / stack_trace

ALTER TABLE `sys_operation_log`
    ADD COLUMN `business_unit_type` varchar(50)  DEFAULT NULL COMMENT '业务单元类型' AFTER `cost_time`,
    ADD COLUMN `target_id`          varchar(100) DEFAULT NULL COMMENT '操作目标ID' AFTER `business_unit_type`,
    ADD COLUMN `before_data`        text         DEFAULT NULL COMMENT '修改前数据（JSON）' AFTER `target_id`,
    ADD COLUMN `after_data`         text         DEFAULT NULL COMMENT '修改后数据（JSON）' AFTER `before_data`,
    ADD COLUMN `stack_trace`        text         DEFAULT NULL COMMENT '异常堆栈' AFTER `after_data`;
