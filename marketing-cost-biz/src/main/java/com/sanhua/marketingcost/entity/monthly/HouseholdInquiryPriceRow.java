package com.sanhua.marketingcost.entity.monthly;

import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;
import java.math.BigDecimal;

/** Versioned source row; joined batch context is read-only. */
@Getter
@Setter
public class HouseholdInquiryPriceRow {
  private Long id;
  private String businessUnitType;
  private String materialCode;
  private String materialName;
  private String specification;
  private Integer revisionNo;
  private Long previousPriceId;
  private Long importBatchId;
  private String sourceRowsJson;
  private BigDecimal unitPrice;
  private BigDecimal householdRate;
  private String pricingFingerprint;
  private String recordFingerprint;
  private String referenceJson;
  private Integer currentFlag;
  private LocalDateTime createdAt;
  private String sourceMonth;
  private String pricingUnit;
  private String currency;
  private String taxBasis;
  private String fileKey;
  private String fileSha256;
}
