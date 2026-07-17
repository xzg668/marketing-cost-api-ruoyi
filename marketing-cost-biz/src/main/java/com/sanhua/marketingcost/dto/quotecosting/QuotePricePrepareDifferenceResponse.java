package com.sanhua.marketingcost.dto.quotecosting;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/** 同一结算明细在财务基准与 OA 锁价两个口径下的价格差异。 */
@Getter
@Setter
public class QuotePricePrepareDifferenceResponse {
  private String settlementKey;
  private String materialCode;
  private String materialName;
  private String itemType;
  private BigDecimal quantity;
  private BigDecimal financeUnitPrice;
  private BigDecimal oaUnitPrice;
  /** OA - 财务。 */
  private BigDecimal unitPriceDifference;
  private BigDecimal financeAmount;
  private BigDecimal oaAmount;
  /** OA - 财务。 */
  private BigDecimal amountDifference;
  /** OA 与财务金额差异占财务金额的百分比。 */
  private BigDecimal differenceRate;
  private boolean different;
}
