package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FactorAdjustExcelParseRow {
  private String sourceSheetName;
  private Integer sourceRowNumber;
  private Long factorIdentityId;
  private Long factorMonthlyPriceId;
  private String factorSeqNo;
  private String factorName;
  private String shortName;
  private String priceSource;
  private BigDecimal price;
  private BigDecimal originalPrice;
  private String unit;
  private String matchMethod;
  private String status;
  private String failReason;
}
