package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SupplierSupplyRatioUpdateRequest {
  private String unit;
  private String materialShape;
  private String supplierCode;
  private BigDecimal supplyRatio;
  private LocalDate effectiveFrom;
  private LocalDate effectiveTo;
}
