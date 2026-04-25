package com.sanhua.marketingcost.service.pricing;

import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * BOM 计算 Resolver —— 委派到 {@link MakePartResolver}（Task #8 实现）。
 *
 * <p>命中策略：按 (material_code, period) 取 lp_make_part_spec → 默认骨架公式或绑定模板。
 */
@Component
public class BomCalcPriceResolver implements PriceResolver {

  private final MakePartResolver makePartResolver;

  public BomCalcPriceResolver(MakePartResolver makePartResolver) {
    this.makePartResolver = makePartResolver;
  }

  @Override
  public PriceTypeEnum priceType() {
    return PriceTypeEnum.BOM_CALC;
  }

  @Override
  public PriceResolveResult resolve(String oaNo, CostRunPartItemDto item, PriceTypeRoute route) {
    if (item == null || item.getPartCode() == null || item.getPartCode().isBlank()) {
      return PriceResolveResult.miss("缺少 partCode");
    }
    // CostRunPartItemDto 暂无 period 字段；MakePartResolver 会回退到最新一条
    BigDecimal price = makePartResolver.resolve(item.getPartCode(), null);
    if (price == null) {
      return PriceResolveResult.miss(
          "MakePartResolver 未取到 " + item.getPartCode() + " 的工艺规格或递归失败");
    }
    return PriceResolveResult.hit(price, "MAKE_PART_SPEC");
  }
}
