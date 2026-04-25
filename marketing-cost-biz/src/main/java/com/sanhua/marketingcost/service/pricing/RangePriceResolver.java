package com.sanhua.marketingcost.service.pricing;

import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import org.springframework.stereotype.Component;

/**
 * 区间价 Resolver —— 占位实现。
 *
 * <p>lp_price_range_item 表 CREATE 缺失（V2 只加了索引），且金标 Excel 中无区间价命中。
 * 真正实现待 Phase 3 与制造件同期完成；当前返回 miss + 标红，让 Service 层 fallback 到下一优先级。
 */
@Component
public class RangePriceResolver implements PriceResolver {

  @Override
  public PriceTypeEnum priceType() {
    return PriceTypeEnum.RANGE;
  }

  @Override
  public PriceResolveResult resolve(String oaNo, CostRunPartItemDto item, PriceTypeRoute route) {
    return PriceResolveResult.miss("[TODO Phase3] 区间价桶尚未实现，标红待补");
  }
}
