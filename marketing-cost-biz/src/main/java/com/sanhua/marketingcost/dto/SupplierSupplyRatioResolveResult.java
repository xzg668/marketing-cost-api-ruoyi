package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SupplierSupplyRatioResolveResult {
  private boolean matched;
  private Long id;
  private String supplierName;
  private String supplierCode;
  private BigDecimal supplyRatio;
  private String sourceType;
  private String sourceBatchNo;
  private String traceMessage;

  public static SupplierSupplyRatioResolveResult miss(String traceMessage) {
    SupplierSupplyRatioResolveResult result = new SupplierSupplyRatioResolveResult();
    result.setMatched(false);
    result.setTraceMessage(traceMessage);
    return result;
  }
}
