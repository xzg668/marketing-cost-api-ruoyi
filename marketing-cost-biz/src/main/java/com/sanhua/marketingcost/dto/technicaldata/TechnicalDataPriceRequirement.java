package com.sanhua.marketingcost.dto.technicaldata;

public record TechnicalDataPriceRequirement(String itemKey, String materialNo, String name, String model,
    String organizationCode, String unit, String currency, java.util.List<String> roles,
    String status, java.math.BigDecimal unitPrice, String message) {
  public boolean requiresEntry() { return "MISSING".equals(status) || "REVISION".equals(status); }
}
