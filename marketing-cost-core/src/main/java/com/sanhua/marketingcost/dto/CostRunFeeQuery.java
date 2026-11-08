package com.sanhua.marketingcost.dto;

/** Exact public-source scopes after resolving each consumer's applicable context. */
public final class CostRunFeeQuery {
  private CostRunFeeQuery() {}

  public record Department(int year, String division, String subject, String businessUnitType) {
    public Department { businessUnitType = optionalUnit(businessUnitType); }
  }
  public record Manufacture(int year, String matchLevel, String matchKey, String businessUnitType) {
    public Manufacture { businessUnitType = optionalUnit(businessUnitType); }
  }
  public record ThreeExpense(int year, String productCategory, String productLine, String businessUnitType) {}
  public record OtherExpense(String productCode) {}
  public record Property(Integer year, String productCode, String businessUnitType) {
    public Property { businessUnitType = optionalUnit(businessUnitType); }
  }
  public record PropertyRule(int year, String productAttr, String businessUnitType) {}
  public record QualityLoss(int year, String bareProductCode, String businessUnitType) {}
  public record Dimension(String dimensionType, String sourceSystem, String sourceValue, String businessUnitType) {}
  public record Rule(String ruleCode, String pricingMonth, String businessUnitType) {}
  public record Material(String materialCode, String organizationCode) {}
  public record NetLossModel(String model, String organizationCode) {}

  private static String optionalUnit(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
