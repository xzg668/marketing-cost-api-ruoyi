package com.sanhua.marketingcost.service.pricing.monthly;

import com.sanhua.marketingcost.enums.MaterialOrganization;
import java.math.BigDecimal;
import java.util.Objects;

/** A coverage lookup request, not the unique identity of an adopted price row. */
public record MonthlyPriceRequest(
    MonthlyPriceFamilyKey family,
    String priceOrgCode,
    NonCuConditions conditions,
    BigDecimal priceBasisQuantity,
    BigDecimal annualUsagePieces) {

  public MonthlyPriceRequest(MonthlyPriceFamilyKey family, String priceOrgCode,
      NonCuConditions conditions, BigDecimal priceBasisQuantity) {
    this(family, priceOrgCode, conditions, priceBasisQuantity, null);
  }

  public MonthlyPriceRequest {
    Objects.requireNonNull(family, "Monthly price family is required");
    priceOrgCode = MaterialOrganization.fromPriceOrgCode(priceOrgCode).getPriceOrgCode();
    Objects.requireNonNull(conditions, "Request conditions are required");
    if (conditions.boardTerms() != null && !"220".equals(priceOrgCode)) {
      throw new IllegalArgumentException("Board terms require price organization 220");
    }
    Objects.requireNonNull(priceBasisQuantity, "Price basis quantity is required");
    if (priceBasisQuantity.signum() < 0) {
      throw new IllegalArgumentException("Price basis quantity cannot be negative");
    }
    priceBasisQuantity = priceBasisQuantity.stripTrailingZeros();
    annualUsagePieces = annualUsagePieces == null || annualUsagePieces.signum() <= 0 ? null : annualUsagePieces.stripTrailingZeros();
  }

  public String materialCode() {
    return family.materialCode();
  }

  /** The BOM keeps its own code, including an absent code after specification matching. */
  public boolean matchesBomMaterialCode(String bomCode) {
    String code = bomCode == null ? "" : bomCode.trim();
    return family.specificationOnly() ? code.isEmpty() : family.materialCode().equals(code);
  }

  public boolean matchesSpecification(String sourceSpecification) {
    return !family.specificationOnly()
        || Objects.equals(family.specification(), sourceSpecification == null ? null : sourceSpecification.trim());
  }
}
