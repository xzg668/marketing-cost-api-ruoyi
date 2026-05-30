package com.sanhua.marketingcost.service.settlement;

import com.sanhua.marketingcost.entity.BomCostingRow;
import java.util.List;

/** 统一 BOM 结算行生成结果；这里只产出候选对象，不执行数据库写入。 */
public record BomSettlementRowBuildResult(
    List<BomCostingRow> costingRows,
    List<BomSettlementSubRefCandidate> subRefs,
    List<BomSettlementSourceRefCandidate> sourceRefs,
    List<String> warnings,
    BomSettlementRowBuildStats stats) {

  public BomSettlementRowBuildResult {
    costingRows = costingRows == null ? List.of() : List.copyOf(costingRows);
    subRefs = subRefs == null ? List.of() : List.copyOf(subRefs);
    sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
  }
}
