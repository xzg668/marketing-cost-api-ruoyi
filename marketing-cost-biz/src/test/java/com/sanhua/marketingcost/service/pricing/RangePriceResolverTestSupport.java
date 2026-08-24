package com.sanhua.marketingcost.service.pricing;

import static org.mockito.Mockito.mock;

import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.PriceRangeFactorRuleMapper;
import com.sanhua.marketingcost.mapper.PriceRangeItemMapper;
import com.sanhua.marketingcost.service.SupplierSupplyRatioResolveService;

/** 测试也必须经过正式供应商选择链路，禁止用构造器绕过生产规则。 */
public final class RangePriceResolverTestSupport {

  private RangePriceResolverTestSupport() {
  }

  public static RangePriceResolver create(PriceRangeItemMapper itemMapper) {
    return create(itemMapper, null, null);
  }

  public static RangePriceResolver create(
      PriceRangeItemMapper itemMapper,
      PriceRangeFactorRuleMapper factorRuleMapper,
      OaFormMapper oaFormMapper) {
    SupplierSupplyRatioResolveService ratioService =
        mock(SupplierSupplyRatioResolveService.class);
    return new RangePriceResolver(
        itemMapper,
        factorRuleMapper,
        oaFormMapper,
        new SupplierPreferredPriceSelector(ratioService));
  }
}
