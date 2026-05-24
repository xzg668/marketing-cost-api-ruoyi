package com.sanhua.marketingcost.dto.ingest;

import java.math.BigDecimal;
import lombok.Data;

@Data
public class QuoteRequestExtraFeeResponse {
  private Long id;
  private Long oaFormItemId;
  private String feeScope;
  private String businessUnitType;
  private String feeCode;
  private String feeName;
  private String feeCategory;
  private BigDecimal amount;
  private String unit;
  private Integer taxIncluded;
  private String allocationMethod;
  private BigDecimal allocatedAmount;
  private String bearer;
  private String projectNo;
  private String sourceType;
  private String sourceFieldName;
  private String sourceFieldPath;
  private String remark;
}
