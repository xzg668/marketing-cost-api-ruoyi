package com.sanhua.marketingcost.service.pricing;

import java.math.BigDecimal;
import java.util.Objects;

/** 一次物料计算的完整输入。板换SUS两侧共用，P1只额外覆盖Cu。 */
public record PriceFactorContext(
    String materialCode,
    PriceMonthSelection months,
    FactorPriceSnapshot financial,
    BigDecimal customerCopperPricePerTon,
    BoardPriceInputs boardInputs) {
  public static final String COPPER = "AVG|1#CU";

  public PriceFactorContext(
      String materialCode,
      PriceMonthSelection months,
      FactorPriceSnapshot financial,
      BigDecimal customerCopperPricePerTon) {
    this(materialCode, months, financial, customerCopperPricePerTon, null);
  }

  public PriceFactorContext {
    materialCode = FactorPriceScope.required(materialCode, "料号", 64);
    Objects.requireNonNull(months, "因素月份必填");
    Objects.requireNonNull(financial, "财务因素快照必填");
    if (boardInputs != null
        && (!"220".equals(financial.scope().priceOrgCode())
            || financial.scope().condition()
                != (boardInputs.finalTender()
                    ? FactorPriceScope.Condition.TENDER
                    : FactorPriceScope.Condition.NON_TENDER))) {
      throw new IllegalArgumentException("板换输入与财务组织/招标条件不一致：" + materialCode);
    }
    if (!months.sourceMonth().equals(financial.scope().priceMonth())) {
      throw new IllegalArgumentException("因素快照来源月与所选月份不一致：" + materialCode);
    }
    if (customerCopperPricePerTon != null && customerCopperPricePerTon.signum() <= 0) {
      throw new IllegalArgumentException("客户Cu必须大于0：" + materialCode);
    }
  }

  public PriceFactorContext withCustomerCopperPerTon(BigDecimal copperPerTon) {
    return new PriceFactorContext(
        materialCode,
        months,
        financial,
        Objects.requireNonNull(copperPerTon, "客户Cu缺失：" + materialCode),
        boardInputs);
  }

  public PriceFactorContext financialOnly() {
    return new PriceFactorContext(materialCode, months, financial, null, boardInputs);
  }

  public PriceFactorContext withBoardInputs(BoardPriceInputs inputs) {
    return new PriceFactorContext(
        materialCode, months, financial, customerCopperPricePerTon, inputs);
  }

  /** 保留公式因素及报价共用的财务Cu基准和完整来源；发布身份、修订和客户条件仍保留。 */
  public PriceFactorContext evidenceForFactors(java.util.Set<String> canonicalKeys) {
    var used = new java.util.LinkedHashMap<String, FactorPriceSnapshot.FactorValue>();
    for (String key : canonicalKeys) {
      String canonical = canonicalCopperAlias(key);
      var factor = financial.factors().get(canonical);
      // 客户SUS可以独立提供该因素；缺少财务值不伪造来源。
      if (factor != null) used.put(canonical, factor);
    }
    // 财务价格准备从底稿提取统一Cu基准，SUS/Pcu等非铜公式也需要这项依据。
    var copper = financial.factors().get(COPPER);
    if (copper != null) used.putIfAbsent(COPPER, copper);
    var selected =
        new FactorPriceSnapshot(
            financial.scope(),
            financial.batchId(),
            financial.revision(),
            financial.fileName(),
            financial.fileSha256(),
            financial.contentHash(),
            used,
            financial.schemeCodes(),
            financial.publishedBy(),
            financial.publishedAt(),
            financial.publicationNote());
    return new PriceFactorContext(
        materialCode, months, selected, customerCopperPricePerTon, boardInputs);
  }

  public String inputSource(String canonicalKey) {
    if (boardInputs != null && boardInputs.customerPricePerTon(canonicalKey) != null)
      return "CUSTOMER_SUS";
    if (COPPER.equals(canonicalCopperAlias(canonicalKey)) && customerCopperPricePerTon != null)
      return "OA_LOCKED";
    return "FINANCE_FACTOR";
  }

  /** 显式目标单位和税价口径；不在取价时猜测单位，也不把缺失因素转换成0。 */
  public BigDecimal requiredPrice(
      String canonicalKey, FactorPriceUnit targetUnit, boolean taxIncluded) {
    String key = canonicalCopperAlias(canonicalKey);
    BigDecimal customerSus = boardInputs == null ? null : boardInputs.customerPricePerTon(key);
    if (customerSus != null) {
      if (!taxIncluded) throw new IllegalArgumentException("客户SUS基价为含税价");
      return FactorPriceUnit.CNY_TON.convert(customerSus, targetUnit);
    }
    var factor = financial.factors().get(key);
    if (factor == null || factor.price() == null) {
      throw new IllegalArgumentException(
          "物料="
              + materialCode
              + "，缺少财务因素="
              + key
              + "，来源月="
              + months.sourceMonth()
              + "，批次="
              + financial.batchId());
    }
    if (factor.taxIncluded() != taxIncluded) {
      throw new IllegalArgumentException("物料=" + materialCode + "，因素=" + key + "，税价口径不一致");
    }
    try {
      FactorPriceUnit sourceUnit = FactorPriceUnit.require(factor.unit());
      if (COPPER.equals(key) && customerCopperPricePerTon != null) {
        // 客户字段已明确为含税元/吨；财务来源值及其单元格始终保留在financial中。
        if (!taxIncluded) throw new IllegalArgumentException("客户Cu为含税价");
        return FactorPriceUnit.CNY_TON.convert(customerCopperPricePerTon, targetUnit);
      }
      return sourceUnit.convert(factor.price(), targetUnit);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(
          "物料=" + materialCode + "，因素=" + key + "，" + error.getMessage(), error);
    }
  }

  private static String canonicalCopperAlias(String canonicalKey) {
    if (canonicalKey != null
        && (canonicalKey.equalsIgnoreCase("AVG|CU") || canonicalKey.equalsIgnoreCase(COPPER)))
      return COPPER;
    return canonicalKey;
  }
}
