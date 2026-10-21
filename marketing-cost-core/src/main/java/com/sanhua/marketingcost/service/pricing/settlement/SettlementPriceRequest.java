package com.sanhua.marketingcost.service.pricing.settlement;

import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.PriceTypeRoute;

/** 消费者组织仅用于验证共享范围和追溯，不拆分同一份结算原稿。 */
public record SettlementPriceRequest(
    String businessUnitType, String materialCode, String accountingMonth,
    String consumerOrgCode, SettlementPriceType priceType) {
  public static SettlementPriceRequest from(
      CostRunPartItemDto item, PriceTypeRoute route, CostRunContext context) {
    if (context == null || item == null || route == null || !route.settlement())
      throw new IllegalArgumentException("结算取价缺少物料、路由或核算上下文");
    String org = item.getPriceOrgCode();
    if (org == null || org.isBlank()) org = context.getPriceOrgCode();
    return new SettlementPriceRequest(context.getBusinessUnitType(), item.getPartCode(),
        context.getPricingMonth(), org, SettlementPriceType.valueOf(route.selectedSourceType()));
  }

  public SettlementPriceRequest {
    var scope = new SettlementScope(businessUnitType, accountingMonth);
    businessUnitType = scope.businessUnitType();
    accountingMonth = scope.priceMonth();
    materialCode = SettlementScope.required(materialCode, "料号", 64);
    consumerOrgCode = SettlementScope.required(consumerOrgCode, "取价组织", 32);
    if (!consumerOrgCode.equals("210") && !consumerOrgCode.equals("220"))
      throw new IllegalArgumentException("结算价格仅供商用 210、板换 220 使用");
    if (priceType == null) throw new IllegalArgumentException("结算价格类型必填");
  }
}
