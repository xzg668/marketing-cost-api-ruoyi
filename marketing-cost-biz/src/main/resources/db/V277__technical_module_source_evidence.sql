-- 来源检查保存的是完整证据快照。正式包装 BOM 的多节点证据可能超过旧 512 字符，
-- 不得截断证据或导致整产品分派失败；保留原值，仅扩充存储容量。
ALTER TABLE lp_quote_tech_module MODIFY source_reference MEDIUMTEXT NULL;
