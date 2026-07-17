package com.sanhua.marketingcost.dto.quotecosting;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/** 财务基准与 OA 锁价的明细对比汇总。 */
@Getter
@Setter
public class QuotePricePrepareDifferenceSummary {
  private int totalCount;
  private int differentCount;
  private BigDecimal financeTotalAmount;
  private BigDecimal oaTotalAmount;
  /** OA - 财务。 */
  private BigDecimal amountDifference;
}
