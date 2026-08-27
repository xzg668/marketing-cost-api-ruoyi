-- 技术协作查找参考 BOM 时，只需要扫描当前组织、来源下的顶层节点。
-- 原索引以顶层料号为第二列，未指定料号的相似 BOM 查询会扫描整组织 BOM。
CREATE INDEX idx_bom_candidate_root
    ON lp_bom_raw_hierarchy (
      price_org_code,
      source_type,
      `level`,
      effective_from,
      effective_to,
      top_product_code,
      bom_purpose
    );
