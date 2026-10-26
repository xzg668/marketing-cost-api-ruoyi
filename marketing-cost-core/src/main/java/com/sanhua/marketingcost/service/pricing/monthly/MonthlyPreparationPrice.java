package com.sanhua.marketingcost.service.pricing.monthly;

import java.math.BigDecimal;

/** Captured preparation amounts and the exact monthly fact that supplies this occurrence. */
public record MonthlyPreparationPrice(int snapshotSchema, String sourceKind,
    MonthlyPriceRequest request, MonthlyAdoptionPlan.Selection selection,
    BigDecimal financialPrice, BigDecimal customerPrice, BigDecimal unitPriceMultiplier,
    String path, BigDecimal quantity, MonthlyPreparationInputs monthlyInputs) {
  public static final String KIND = "MONTHLY_PRICE";

  public MonthlyPreparationPrice {
    if (snapshotSchema != 1 || !KIND.equals(sourceKind)) throw new IllegalArgumentException("未知月价准备快照");
    java.util.Objects.requireNonNull(request);
    java.util.Objects.requireNonNull(selection);
    java.util.Objects.requireNonNull(financialPrice);
    java.util.Objects.requireNonNull(customerPrice);
    if (unitPriceMultiplier == null || unitPriceMultiplier.signum() <= 0)
      throw new IllegalArgumentException("计价单位转换倍数必须为正数");
    if ((path == null) != (quantity == null)) throw new IllegalArgumentException("计价路径与用量必须同时提供");
  }

  public static MonthlyPreparationPrice read(QuotationPriceSession.Value value) {
    return new MonthlyPreparationPrice(1, KIND, value.request(), value.selection(), value.financialPrice(),
        value.customerPrice(), BigDecimal.ONE, null, null, null);
  }

  public QuotationPriceSession.Value value() {
    return new QuotationPriceSession.Value(request, selection, financialPrice, customerPrice);
  }

  /** Preserve the source unit price while recording the existing calculator's unit conversion. */
  public MonthlyPreparationPrice withUnitPriceMultiplier(BigDecimal multiplier) {
    return new MonthlyPreparationPrice(snapshotSchema, sourceKind, request, selection,
        financialPrice, customerPrice, multiplier, path, quantity, monthlyInputs);
  }

  public MonthlyPreparationPrice withInputs(MonthlyPreparationInputs inputs) {
    if (path == null || !MonthlyBomPricingScope.pathKey(path).equals(inputs.rootPath())
        || !selection.equals(inputs.plan().selections().get(request)))
      throw new IllegalArgumentException("普通准备与月价实际输入不一致");
    return new MonthlyPreparationPrice(snapshotSchema, sourceKind, request, selection,
        financialPrice, customerPrice, unitPriceMultiplier, path, quantity, inputs);
  }

  public static MonthlyPreparationPrice of(QuotationPriceSession.Value value, String path, BigDecimal quantity) {
    return new MonthlyPreparationPrice(1, KIND, value.request(), value.selection(), value.financialPrice(),
        value.customerPrice(), BigDecimal.ONE, MonthlyBomPricingScope.pathKey(path), quantity, null);
  }
}
