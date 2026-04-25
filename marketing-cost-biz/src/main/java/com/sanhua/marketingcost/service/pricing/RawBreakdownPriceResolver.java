package com.sanhua.marketingcost.service.pricing;

import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import org.springframework.stereotype.Component;

/**
 * 原材料拆解 Resolver —— 占位实现。
 *
 * <p>对应 Excel"价格来源 = 原材料拆解"（多原料按比例 + 加工费）。
 * 待 Phase 3 lp_raw_material_breakdown 表落地后实现，本期返回 miss 标红。
 */
@Component
public class RawBreakdownPriceResolver implements PriceResolver {

  @Override
  public PriceTypeEnum priceType() {
    return PriceTypeEnum.RAW_BREAKDOWN;
  }

  @Override
  public PriceResolveResult resolve(String oaNo, CostRunPartItemDto item, PriceTypeRoute route) {
    return PriceResolveResult.miss("[TODO Phase3] 原材料拆解桶待 lp_raw_material_breakdown 实现");
  }
}
