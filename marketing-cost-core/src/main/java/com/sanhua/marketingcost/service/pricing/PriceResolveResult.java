package com.sanhua.marketingcost.service.pricing;

import java.math.BigDecimal;

/**
 * Resolver 取价结果。
 *
 * <ul>
 *   <li>unitPrice：命中时为有效价格，未命中或暂未实现为 null</li>
 *   <li>priceSource：见机表的"价格来源"列文案，例如"固定采购价"/"联动价"/"家用结算价"；
 *       缺价标红时为 "NO_ROUTE"（未配路由）/ "ERROR"（路由有但全 miss / 输入非法）</li>
 *   <li>remark：调试 / 标红信息（fallback、缺数据、暂未实现等）</li>
 * </ul>
 *
 * <p>用 record 保证不可变；调用方请勿在算后修改。
 */
public record PriceResolveResult(BigDecimal unitPrice, String priceSource, String remark) {

  /** 缺价标红：未配价格类型路由（路由表无该料号 × period 行）。 */
  public static final String SOURCE_NO_ROUTE = "NO_ROUTE";
  /** 缺价标红：路由有候选但全部 Resolver miss / 输入非法 / Resolver 未注册。 */
  public static final String SOURCE_ERROR = "ERROR";

  /** 工厂：构造命中结果。 */
  public static PriceResolveResult hit(BigDecimal unitPrice, String priceSource) {
    return new PriceResolveResult(unitPrice, priceSource, "");
  }

  /**
   * 工厂：单个 Resolver 未命中。priceSource 留空，由上游 Service 汇总后改写为 ERROR / NO_ROUTE。
   * Resolver 内部使用，不直接进 DB。
   */
  public static PriceResolveResult miss(String reason) {
    return new PriceResolveResult(null, "", reason);
  }

  /**
   * 工厂：路由表无候选 → 标 NO_ROUTE。
   * 业务约定：缺路由是配置缺失，提示"去价格类型表录入"。
   */
  public static PriceResolveResult noRoute(String materialCode) {
    return new PriceResolveResult(
        null, SOURCE_NO_ROUTE, "未配价格类型路由：去价格类型表录入 " + materialCode);
  }

  /**
   * 工厂：路由有候选但全部 miss / 输入非法 → 标 ERROR。
   * remark 由调用方拼具体上下文（例如尝试过的桶名 + 最后一次 miss 原因）。
   */
  public static PriceResolveResult error(String remark) {
    return new PriceResolveResult(null, SOURCE_ERROR, remark);
  }
}
