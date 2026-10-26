package com.sanhua.marketingcost.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PackagePriceRequest {
  private Long oaFormItemId;
  @com.fasterxml.jackson.annotation.JsonIgnore
  private java.util.Map<String, String> householdChildPaths = java.util.Map.of();
  @com.fasterxml.jackson.annotation.JsonIgnore
  private String sourceParentPath;
  @com.fasterxml.jackson.annotation.JsonIgnore
  private Long sourceParentHierarchyId;
  @com.fasterxml.jackson.annotation.JsonIgnore
  private com.sanhua.marketingcost.service.pricing.monthly.QuotationPriceSession.Scope monthlyPrices =
      new com.sanhua.marketingcost.service.pricing.monthly.QuotationPriceSession.Scope();
  @com.fasterxml.jackson.annotation.JsonIgnore
  private com.sanhua.marketingcost.service.pricing.SupplierPriceReviewContext supplierPriceReviewContext;
  private String packageMaterialCode;
  @com.fasterxml.jackson.annotation.JsonIgnore
  private String businessUnitType;
  @com.fasterxml.jackson.annotation.JsonIgnore
  private com.sanhua.marketingcost.enums.QuotePriceScenarioType priceScenarioType;
  @com.fasterxml.jackson.annotation.JsonIgnore
  private java.util.Map<String, java.math.BigDecimal> variableOverrides = java.util.Map.of();
  private String priceOrgCode;
  private String periodMonth;
  private String quoteNo;
  private String oaNo;
  private String topProductCode;
  private String bomPurpose;
  private String sourceType;
  private LocalDate asOfDate;
  private LocalDateTime priceAsOfTime;
  private String calcBatchId;
}
