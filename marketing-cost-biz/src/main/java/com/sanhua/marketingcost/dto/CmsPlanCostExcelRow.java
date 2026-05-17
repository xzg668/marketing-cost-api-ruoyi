package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CmsPlanCostExcelRow {
  private Integer rowNo;
  private String firstUnitCode;
  private String firstUnitName;
  private String parentCode;
  private String parentName;
  private String parentSpec;
  private String parentType;
  private String unit;
  private BigDecimal workingHours;
  private LocalDate effectiveDate;
  private String effectivePeriod;
  private BigDecimal mainMaterialCost;
  private BigDecimal auxMaterialCost;
  private BigDecimal salaryCost;
  private BigDecimal fundCost;
  private BigDecimal lossCost;
  private BigDecimal totalPlanCost;
  private String businessStatus;
  private String unapprovedItems;
  private String description;
  private String oaNo;
}
