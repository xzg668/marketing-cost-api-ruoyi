package com.sanhua.marketingcost.dto.quotecosting;

import lombok.Data;

/** OA 整单一键核算请求。 */
@Data
public class QuoteBatchCostRunRequest {
  private String mode = "ALL";
  private String periodMonth;
}
