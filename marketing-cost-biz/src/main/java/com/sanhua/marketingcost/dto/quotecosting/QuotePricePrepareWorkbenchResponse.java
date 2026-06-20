package com.sanhua.marketingcost.dto.quotecosting;

import com.sanhua.marketingcost.dto.priceprepare.PricePrepareBatchPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGapPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareItemPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareReadinessResult;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class QuotePricePrepareWorkbenchResponse {
  private String oaNo;
  private Long oaFormItemId;
  private String topProductCode;
  private String periodMonth;
  private String latestPriceTypeConfirmNo;
  private PricePrepareReadinessResult readiness;
  private PricePrepareBatchPageResponse batches;
  private PricePrepareItemPageResponse items;
  private PricePrepareGapPageResponse gaps;
  private PricePrepareGenerateResult generatedResult;
}
