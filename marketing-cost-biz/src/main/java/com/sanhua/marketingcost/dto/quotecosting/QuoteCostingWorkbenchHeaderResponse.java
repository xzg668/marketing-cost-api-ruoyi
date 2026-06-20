package com.sanhua.marketingcost.dto.quotecosting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class QuoteCostingWorkbenchHeaderResponse {
  private Long id;
  private String oaNo;
  private String sourceType;
  private String sourceSystem;
  private String externalFormNo;
  private String processCode;
  private String processName;
  private String quoteScenario;
  private LocalDate applyDate;
  private String customer;
  private String applicantUnit;
  private String applicantDept;
  private String applicantOffice;
  private String applicantName;
  private BigDecimal copperPrice;
  private BigDecimal zincPrice;
  private BigDecimal aluminumPrice;
  private BigDecimal steelPrice;
  private BigDecimal silverPrice;
  private BigDecimal goldPrice;
  private BigDecimal sus304Price;
  private BigDecimal sus316lPrice;
  private BigDecimal otherMaterial;
  private BigDecimal baseShipping;
  private String calcStatus;
  private String classificationStatus;
  private String remark;
  private String businessUnitType;
  private String accountingPeriodMonth;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
