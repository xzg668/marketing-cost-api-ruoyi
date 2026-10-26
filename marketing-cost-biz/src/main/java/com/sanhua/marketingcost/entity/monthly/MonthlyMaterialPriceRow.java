package com.sanhua.marketingcost.entity.monthly;

import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Database transport row; domain invariants are checked by the storage boundary. */
@Getter
@Setter
public class MonthlyMaterialPriceRow {
  private Long id;
  private String applicabilityHash;
  private String applicabilityJson;
  private Integer organizationMask;
  private String pricingType;
  private BigDecimal unitPrice;
  private BigDecimal householdRate;
  private String sourceEvidenceHash;
  private String sourceEvidenceJson;
  private Long householdSourceId;
  private String firstUseJson;
  private String createdBy;
  private LocalDateTime createdAt;
  private String familyHash;
  private String pricingMonth;
  private String businessUnitType;
  private String materialCode;
  private String specification;
  private String pricingUnit;
  private String currency;
  private String taxBasis;
  private String requestedSupplierCode;
  private String sourceType;
  private Long sourceRecordId;
  private String supplierCode;
  private String supplierName;
}
