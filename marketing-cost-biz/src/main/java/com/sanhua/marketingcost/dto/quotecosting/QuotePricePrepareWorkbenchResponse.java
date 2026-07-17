package com.sanhua.marketingcost.dto.quotecosting;

import com.sanhua.marketingcost.dto.priceprepare.PricePrepareBatchPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGapPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareItemPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareReadinessResult;
import java.math.BigDecimal;
import java.util.List;
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
  private PricePrepareGenerateResult financeGeneratedResult;
  private QuotePricePrepareScenarioResponse financeScenario;
  private QuotePricePrepareScenarioResponse oaScenario;
  private List<QuotePricePrepareDifferenceResponse> differences;
  private QuotePricePrepareDifferenceSummary differenceSummary;
  /** 数据库核算口径，单位元/公斤。 */
  private BigDecimal financeCuPricePerKg;
  /** 页面展示口径，单位元/吨。 */
  private BigDecimal financeCuPricePerTon;
  private Long financeBasePriceId;
}
