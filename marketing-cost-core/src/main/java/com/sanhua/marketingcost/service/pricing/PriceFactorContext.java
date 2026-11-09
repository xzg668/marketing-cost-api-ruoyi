package com.sanhua.marketingcost.service.pricing;

import java.math.BigDecimal;
import java.util.Objects;

/** 一次物料计算的完整输入；客户材料基价只用于本报价的P1。 */
public record PriceFactorContext(
    String materialCode,
    PriceMonthSelection months,
    FactorPriceSnapshot financial,
    @com.fasterxml.jackson.annotation.JsonFormat(
            shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        BigDecimal customerCopperPricePerTon,
    BoardPriceInputs boardInputs,
    @com.fasterxml.jackson.annotation.JsonFormat(
            shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        BigDecimal customerZincPricePerTon,
    CustomerMaterialPrices customerMaterialPrices,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        FactorPriceSnapshot.CuBand customerBand) {
  public static final String COPPER = "AVG|1#CU";
  public static final String ZINC = "AVG|1#ZN";

  public PriceFactorContext(String materialCode, PriceMonthSelection months, FactorPriceSnapshot financial,
      BigDecimal copper, BoardPriceInputs board, BigDecimal zinc, CustomerMaterialPrices prices) {
    this(materialCode, months, financial, copper, board, zinc, prices,
        financial == null ? null : financial.customerBand(prices == null ? copper : prices.copper()));
  }

  /** Existing snapshots and callers retain the captured Cu/Zn representation. */
  public PriceFactorContext(String materialCode, PriceMonthSelection months,
      FactorPriceSnapshot financial, BigDecimal copper, BoardPriceInputs board, BigDecimal zinc) {
    this(materialCode, months, financial, copper, board, zinc, null);
  }

  /** Saved historical contexts have no customer zinc value. */
  public PriceFactorContext(String materialCode, PriceMonthSelection months,
      FactorPriceSnapshot financial, BigDecimal customerCopperPricePerTon, BoardPriceInputs boardInputs) {
    this(materialCode, months, financial, customerCopperPricePerTon, boardInputs, null);
  }

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
    if (customerZincPricePerTon != null && customerZincPricePerTon.signum() <= 0) {
      throw new IllegalArgumentException("客户Zn必须大于0：" + materialCode);
    }
  }

  public PriceFactorContext withCustomerCopperPerTon(BigDecimal copperPerTon) {
    Objects.requireNonNull(copperPerTon, "客户Cu缺失：" + materialCode);
    if (customerMaterialPrices != null) {
      var prices = customerMaterialPrices;
      return withCustomerMaterials(new CustomerMaterialPrices(copperPerTon, prices.zinc(),
          prices.silver(), prices.tin(), prices.sus304(), prices.sus316()));
    }
    return new PriceFactorContext(
        materialCode,
        months,
        financial,
        copperPerTon,
        boardInputs, customerZincPricePerTon, customerMaterialPrices);
  }

  public PriceFactorContext withCustomerMetalsPerTon(BigDecimal copperPerTon, BigDecimal zincPerTon) {
    return new PriceFactorContext(materialCode, months, financial, copperPerTon, boardInputs, zincPerTon);
  }

  public PriceFactorContext withCustomerMaterials(CustomerMaterialPrices prices) {
    return new PriceFactorContext(materialCode, months, financial, null, boardInputs, null,
        Objects.requireNonNull(prices));
  }

  public boolean hasCustomerMaterialPrices() {
    return customerCopperPricePerTon != null || customerZincPricePerTon != null
        || customerMaterialPrices != null && !customerMaterialPrices.isEmpty();
  }

  public PriceFactorContext financialOnly() {
    return new PriceFactorContext(materialCode, months, financial, null, boardInputs);
  }

  public PriceFactorContext withBoardInputs(BoardPriceInputs inputs) {
    return new PriceFactorContext(
        materialCode, months, financial, customerCopperPricePerTon, inputs, customerZincPricePerTon,
        customerMaterialPrices);
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
            financial.publicationNote(),
            financial.cuBands().stream().map(band -> band.retain(used.keySet())).toList());
    return new PriceFactorContext(
        materialCode, months, selected, customerCopperPricePerTon, boardInputs, customerZincPricePerTon,
        customerMaterialPrices);
  }

  public String inputSource(String canonicalKey) {
    if (customerMaterialPricePerTon(canonicalKey) != null) return "OA_LOCKED";
    if (boardInputs != null && boardInputs.customerPricePerTon(canonicalKey) != null)
      return "CUSTOMER_SUS";
    if (customerMetalPricePerTon(canonicalKey) != null)
      return "OA_LOCKED";
    if (customerBand != null && CustomerMaterialPrices.usesCopperBand(canonicalKey)) return "FINANCE_CU_BAND";
    return "FINANCE_FACTOR";
  }

  /** 显式目标单位和税价口径；不在取价时猜测单位，也不把缺失因素转换成0。 */
  public BigDecimal requiredPrice(
      String canonicalKey, FactorPriceUnit targetUnit, boolean taxIncluded) {
    String key = canonicalCopperAlias(canonicalKey);
    BigDecimal customerSus = BoardStainlessGrade.fromFactorKey(key) == null
        ? null : customerMaterialPricePerTon(key);
    if (customerSus == null)
      customerSus = boardInputs == null ? null : boardInputs.customerPricePerTon(key);
    if (customerSus != null) {
      if (!taxIncluded) throw new IllegalArgumentException("客户材料基价为含税价");
      return FactorPriceUnit.CNY_TON.convert(customerSus, targetUnit);
    }
    var factor = customerBand == null || !CustomerMaterialPrices.usesCopperBand(key) || customerMetalPricePerTon(key) != null
        ? financial.factors().get(key) : customerBand.factors().get(key);
    if (factor == null || factor.price() == null) {
      throw new IllegalArgumentException(
          "物料="
              + materialCode
              + "，缺少财务因素="
              + key + (customerBand == null ? "" : "，客户铜价档=" + customerBand.schemeCode())
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
      BigDecimal customerMetal = customerMetalPricePerTon(key);
      if (customerMetal != null) {
        // 客户字段已明确为含税元/吨；财务来源值及其单元格始终保留在financial中。
        if (!taxIncluded) throw new IllegalArgumentException("客户材料基价为含税价");
        return FactorPriceUnit.CNY_TON.convert(customerMetal, targetUnit);
      }
      return sourceUnit.convert(factor.price(), targetUnit);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(
          "物料=" + materialCode + "，因素=" + key + "，" + error.getMessage(), error);
    }
  }

  private BigDecimal customerMetalPricePerTon(String canonicalKey) {
    String key = canonicalCopperAlias(canonicalKey);
    BigDecimal customer = customerMaterialPricePerTon(key);
    if (customer != null) return customer;
    return COPPER.equals(key) ? customerCopperPricePerTon : ZINC.equals(key) ? customerZincPricePerTon : null;
  }

  private BigDecimal customerMaterialPricePerTon(String key) {
    if (customerMaterialPrices == null) return null;
    // Stainless overrides belong to board rules; commercial formulas cannot inherit them.
    if (BoardStainlessGrade.fromFactorKey(key) != null
        && !"220".equals(financial.scope().priceOrgCode())) return null;
    return customerMaterialPrices.pricePerTon(key);
  }

  private static String canonicalCopperAlias(String canonicalKey) {
    return CustomerMaterialPrices.canonicalKey(canonicalKey);
  }
}
