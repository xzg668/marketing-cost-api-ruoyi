package com.sanhua.marketingcost.service.pricing;

import java.math.BigDecimal;
import java.util.Locale;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sanhua.marketingcost.entity.OaForm;

/** Quote-local, tax-included base prices, normalized to CNY/ton before calculation. */
public record CustomerMaterialPrices(BigDecimal copper, BigDecimal zinc, BigDecimal silver,
    BigDecimal tin, BigDecimal sus304, BigDecimal sus316) {
  public static final String CALCULATION_VERSION = "MONTHLY_MATERIAL_V4";
  public CustomerMaterialPrices {
    requirePositive(copper, "Cu"); requirePositive(zinc, "Zn");
    requirePositive(silver, "Ag"); requirePositive(tin, "Sn");
    requirePositive(sus304, "SUS304"); requirePositive(sus316, "SUS316");
  }

  public static CustomerMaterialPrices copperAndZinc(BigDecimal copper, BigDecimal zinc) {
    return new CustomerMaterialPrices(copper, zinc, null, null, null, null);
  }

  /** The quotation form labels these values as tax-included CNY/ton. */
  public static CustomerMaterialPrices fromQuotation(OaForm form) {
    return new CustomerMaterialPrices(positive(form.getCopperPrice()), positive(form.getZincPrice()),
        positive(form.getSilverPrice()), null, positive(form.getSus304Price()), positive(form.getSus316lPrice()));
  }

  private static BigDecimal positive(BigDecimal value) {
    return value != null && value.signum() > 0 ? value : null;
  }

  public BigDecimal pricePerTon(String factorKey) {
    return switch (canonicalKey(factorKey)) {
      case "AVG|1#CU" -> copper;
      case "AVG|1#ZN" -> zinc;
      case "AVG|1#AG" -> silver;
      case "AVG|1#SN" -> tin;
      default -> {
        var grade = BoardStainlessGrade.fromFactorKey(factorKey);
        yield grade == null ? null : grade == BoardStainlessGrade.SUS304 ? sus304 : sus316;
      }
    };
  }

  @JsonIgnore public boolean isEmpty() {
    return copper == null && zinc == null && silver == null && tin == null
        && sus304 == null && sus316 == null;
  }

  public static boolean affects(String factorKey) {
    return switch (canonicalKey(factorKey)) {
      case "AVG|1#CU", "AVG|1#ZN", "AVG|1#AG", "AVG|1#SN" -> true;
      default -> BoardStainlessGrade.fromFactorKey(factorKey) != null;
    };
  }

  public static String canonicalKey(String key) {
    String value = key == null ? "" : key.trim().toUpperCase(Locale.ROOT);
    return switch (value) {
      case "AVG|CU" -> "AVG|1#CU";
      case "AVG|ZN" -> "AVG|1#ZN";
      case "AVG|AG" -> "AVG|1#AG";
      case "AVG|SN" -> "AVG|1#SN";
      default -> value;
    };
  }

  private static void requirePositive(BigDecimal value, String factor) {
    if (value != null && value.signum() <= 0)
      throw new IllegalArgumentException("客户" + factor + "基价必须大于0；未指定请留空");
  }
}
