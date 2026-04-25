package com.sanhua.marketingcost.service.pricing;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.entity.PriceFixedItem;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.PriceFixedItemMapper;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 固定价 Resolver —— 查 lp_price_fixed_item，按 effective_from 倒序取最新一条。
 *
 * <p>对应 Excel"价格来源 = 固定采购价"。
 */
@Component
public class FixedPriceResolver implements PriceResolver {

  private final PriceFixedItemMapper priceFixedItemMapper;

  public FixedPriceResolver(PriceFixedItemMapper priceFixedItemMapper) {
    this.priceFixedItemMapper = priceFixedItemMapper;
  }

  @Override
  public PriceTypeEnum priceType() {
    return PriceTypeEnum.FIXED;
  }

  @Override
  public PriceResolveResult resolve(String oaNo, CostRunPartItemDto item, PriceTypeRoute route) {
    String code = item.getPartCode();
    if (!StringUtils.hasText(code)) {
      return PriceResolveResult.miss("partCode 为空，无法查固定价");
    }
    List<PriceFixedItem> rows =
        priceFixedItemMapper.selectList(
            Wrappers.lambdaQuery(PriceFixedItem.class)
                .eq(PriceFixedItem::getMaterialCode, code)
                .orderByDesc(PriceFixedItem::getEffectiveFrom)
                .orderByDesc(PriceFixedItem::getId)
                .last("LIMIT 1"));
    if (rows.isEmpty() || rows.get(0).getFixedPrice() == null) {
      return PriceResolveResult.miss("lp_price_fixed_item 无记录: " + code);
    }
    return PriceResolveResult.hit(rows.get(0).getFixedPrice(), "固定采购价");
  }
}
