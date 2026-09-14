package com.sanhua.marketingcost.service.technicaldata;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TechnicalDataAuxiliaryHistoryRow {
  private Long sourceProductId;
  private Long sourceVersionId;
  private Integer sourceVersionNo;
  private String materialNo;
  private String productName;
  private String productModel;
  private String validFromMonth;
  private String contentFingerprint;
  private BigDecimal totalAmount;
  private Integer itemCount;
}
