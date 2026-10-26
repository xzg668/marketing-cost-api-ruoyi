package com.sanhua.marketingcost.service.pricing.monthly;

import java.math.BigDecimal;

/** Optional quotation input, validated only when applicable tier rows actually need a quantity. */
final class OdsTierAnnualUsage {
  private static final BigDecimal MONTHS = BigDecimal.valueOf(12);

  private OdsTierAnnualUsage() {}

  record Quantity(BigDecimal annualPieces) {
    Quantity {
      if (annualPieces == null || annualPieces.signum() <= 0)
        throw new IllegalArgumentException("阶梯取价需要当前报价产品的正数年用量");
      annualPieces = annualPieces.stripTrailingZeros();
    }

  }

  /** Convert monthly band bounds once; both source selection and saved applicability use this. */
  static QuantityInterval annualBand(QuantityInterval monthlyBand) {
    return new QuantityInterval(monthlyBand.lower()==null?null:monthlyBand.lower().multiply(MONTHS),
        monthlyBand.lowerInclusive(),monthlyBand.upper()==null?null:monthlyBand.upper().multiply(MONTHS),
        monthlyBand.upperInclusive());
  }

  static Quantity require(BigDecimal annualValue, String annualUnit) {
    if (annualValue == null) throw new IllegalArgumentException("阶梯取价缺少当前报价产品的年用量");
    if (annualValue.signum() <= 0)
      throw new IllegalArgumentException("阶梯取价的当前报价产品年用量须大于零，请核对产品明细");
    if (annualUnit == null || annualUnit.isBlank())
      throw new IllegalArgumentException("阶梯取价缺少当前报价产品的年用量单位，不能猜测数量转换");
    var pieces=new AnnualUsageInput(annualValue,annualUnit).piecesIfKnown();
    if(pieces==null)throw new IllegalArgumentException("阶梯取价不能明确转换年用量单位："+annualUnit);
    return new Quantity(pieces);
  }
}
