package com.sanhua.marketingcost.service.pricing;

import java.math.BigDecimal;

/**
 * Resolver 取价结果。
 *
 * <ul>
 *   <li>unitPrice：命中时为有效价格，未命中或暂未实现为 null</li>
 *   <li>priceSource：见机表的"价格来源"列文案，例如"固定采购价"/"联动价"/"家用结算价"</li>
 *   <li>remark：调试 / 标红信息（fallback、缺数据、暂未实现等）</li>
 * </ul>
 *
 * <p>用 record 保证不可变；调用方请勿在算后修改。
 */
public record PriceResolveResult(BigDecimal unitPrice, String priceSource, String remark) {

  /** 工厂：构造命中结果。 */
  public static PriceResolveResult hit(BigDecimal unitPrice, String priceSource) {
    return new PriceResolveResult(unitPrice, priceSource, "");
  }

  /** 工厂：未命中结果，附原因。 */
  public static PriceResolveResult miss(String reason) {
    return new PriceResolveResult(null, "", reason);
  }
}
