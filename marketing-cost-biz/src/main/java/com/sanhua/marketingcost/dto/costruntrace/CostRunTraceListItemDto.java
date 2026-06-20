package com.sanhua.marketingcost.dto.costruntrace;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CostRunTraceListItemDto {
  private Long id;
  private Long costRunVersionId;
  private String costRunNo;
  private String versionNo;
  private String oaNo;
  private Long oaFormItemId;
  private String productCode;
  private String pricingMonth;
  private String traceType;
  private String traceKey;
  private Long partItemId;
  private Long costItemId;
  private String materialCode;
  private String materialName;
  private String costCode;
  private String costName;
  private String sourceType;
  private String sourceBatchNo;
  private Long sourceRefId;
  private BigDecimal unitPrice;
  private BigDecimal quantity;
  private BigDecimal baseAmount;
  private BigDecimal rate;
  private BigDecimal amount;
  private String summary;
  private String businessUnitType;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
