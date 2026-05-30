package com.sanhua.marketingcost.service.settlement;

import java.util.List;

/** 副产品附加行适配层读取结果；统一引擎只消费内存候选，不直接访问数据库。 */
public record BomByproductSettlementReadResult(
    List<BomSettlementByproduct> byproducts,
    List<BomSettlementScrapRef> scrapRefs,
    List<String> warnings) {

  public BomByproductSettlementReadResult {
    byproducts = byproducts == null ? List.of() : List.copyOf(byproducts);
    scrapRefs = scrapRefs == null ? List.of() : List.copyOf(scrapRefs);
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
  }
}
