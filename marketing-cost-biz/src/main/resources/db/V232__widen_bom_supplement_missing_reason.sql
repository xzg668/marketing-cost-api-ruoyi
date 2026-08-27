-- BOM 检查可能同时返回多条结构缺口，64 字符不足以保存完整、可追溯的业务原因。
ALTER TABLE lp_bom_supplement_task
  MODIFY COLUMN missing_reason VARCHAR(1000) DEFAULT NULL COMMENT '缺失原因';
