package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingBuildResponse;
import com.sanhua.marketingcost.entity.BomCostingRow;
import java.util.List;

public interface QuoteProductBomCostingBuildService {
  record Preview(List<BomCostingRow> rows,
      com.sanhua.marketingcost.service.pricing.monthly.MonthlyBomPricingScope.Scope pricingScope) {
    public Preview {
      rows = List.copyOf(rows);
      java.util.Objects.requireNonNull(pricingScope);
    }
  }

  /** 从同一不可变有效树只读规划当前计价行；不更新结算行、准备记录或月价。 */
  Preview previewFromEffectiveBom(
      Long oaFormItemId, String effectiveBuildBatchId);

  /** 只从不可变最终有效BOM生成当前OA产品行的第2步结算行。 */
  QuoteBomCostingBuildResponse buildFromEffectiveBom(
      Long oaFormItemId, String effectiveBuildBatchId);
}
