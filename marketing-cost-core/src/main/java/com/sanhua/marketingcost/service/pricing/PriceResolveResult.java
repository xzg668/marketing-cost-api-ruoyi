package com.sanhua.marketingcost.service.pricing;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Resolver 取价结果。
 *
 * <ul>
 *   <li>unitPrice：命中时为有效价格，未命中或暂未实现为 null</li>
 *   <li>priceSource：见机表的"价格来源"列文案，例如"固定采购价"/"联动价"/"家用结算价"；
   *       无适用来源为 "MISSING_PRICE"，计算失败为 "ERROR"</li>
 *   <li>remark：调试 / 标红信息（fallback、缺数据、暂未实现等）</li>
 * </ul>
 *
 * <p>用 record 保证不可变；调用方请勿在算后修改。
 */
public record PriceResolveResult(
    BigDecimal unitPrice,
    String priceSource,
    String remark,
    Long resultRefId,
    String failureCode,
    PriceResolveEvidence evidence) {

  public PriceResolveResult(BigDecimal unitPrice, String priceSource, String remark) {
    this(unitPrice, priceSource, remark, null, null, PriceResolveEvidence.none());
  }

  public PriceResolveResult(
      BigDecimal unitPrice, String priceSource, String remark, Long resultRefId) {
    this(unitPrice, priceSource, remark, resultRefId, null, PriceResolveEvidence.none());
  }

  public PriceResolveResult {
    evidence = evidence == null ? PriceResolveEvidence.none() : evidence;
  }

  /** 当前范围没有可用价格来源。 */
  public static final String SOURCE_MISSING_PRICE = "MISSING_PRICE";
  /** 缺价标红：路由有候选但全部 Resolver miss / 输入非法 / Resolver 未注册。 */
  public static final String SOURCE_ERROR = "ERROR";

  /** 工厂：构造命中结果。 */
  public static PriceResolveResult hit(BigDecimal unitPrice, String priceSource) {
    return hit(unitPrice, priceSource, null);
  }

  /** 工厂：构造带来源行 ID 的命中结果。 */
  public static PriceResolveResult hit(BigDecimal unitPrice, String priceSource, Long resultRefId) {
    return new PriceResolveResult(
        unitPrice, priceSource, "", resultRefId, null, PriceResolveEvidence.none());
  }

  /** 工厂：构造带供应商、生效期和历史价标记的命中结果。 */
  public static PriceResolveResult hit(
      BigDecimal unitPrice,
      String priceSource,
      String remark,
      Long resultRefId,
      PriceResolveEvidence evidence) {
    return new PriceResolveResult(unitPrice, priceSource, remark, resultRefId, null, evidence);
  }

  /**
   * 工厂：单个 Resolver 未命中。priceSource 留空，由上游汇总实际缺价或计算错误。
   * Resolver 内部使用，不直接进 DB。
   */
  public static PriceResolveResult miss(String reason) {
    return new PriceResolveResult(
        null, "", reason, null, null, PriceResolveEvidence.none());
  }

  /** 工厂：单个 Resolver 结构化未命中。 */
  public static PriceResolveResult miss(String failureCode, String reason) {
    return new PriceResolveResult(
        null, "", reason, null, failureCode, PriceResolveEvidence.none());
  }

  /** 按完整顺序查找后没有候选来源。 */
  public static PriceResolveResult noAvailablePrice(String materialCode) {
    return new PriceResolveResult(
        null,
        SOURCE_MISSING_PRICE,
        "当前月份和组织没有可用价格来源：" + materialCode,
        null,
        SOURCE_MISSING_PRICE,
        PriceResolveEvidence.none());
  }

  /**
   * 工厂：路由有候选但全部 miss / 输入非法 → 标 ERROR。
   * remark 由调用方拼具体上下文（例如尝试过的桶名 + 最后一次 miss 原因）。
   */
  public static PriceResolveResult error(String remark) {
    return new PriceResolveResult(
        null, SOURCE_ERROR, remark, null, SOURCE_ERROR, PriceResolveEvidence.none());
  }

  public boolean carriedForward() {
    return evidence.carriedForward();
  }

  /** 无适用规则或区间可继续查下一类型；已选规则计算和数据冲突必须阻断。 */
  public static boolean isBlockingFailure(String code) {
    return code != null && !java.util.Set.of(
        MaterialPriceMonthSelector.MATERIAL_MONTH_PRICE_MISSING,
        "RANGE_RULE_MISSING", "RANGE_BAND_MISSING").contains(code);
  }

  public boolean hasBlockingFailure() {
    return isBlockingFailure(failureCode);
  }

  public PriceResolveResult withPriority(com.sanhua.marketingcost.dto.PriceTypeRoute route) {
    return new PriceResolveResult(unitPrice, priceSource, remark, resultRefId, failureCode,
        evidence.withPriority(route));
  }

  public LocalDate effectiveFrom() {
    return evidence.effectiveFrom();
  }

  public LocalDate effectiveTo() {
    return evidence.effectiveTo();
  }

  public String warningMessage() {
    return evidence.warningMessage();
  }
}
