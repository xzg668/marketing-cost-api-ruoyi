package com.sanhua.marketingcost.service.pricing;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.entity.PriceSettleItem;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.PriceSettleItemMapper;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 结算价 Resolver —— 查 lp_price_settle_item，对应 Excel"家用结算价"。
 *
 * <p>取价优先级：linkedSettlePrice > baseSettlePrice > plannedPrice
 * （联动结算价已包含上浮系数，最贴合财务口径）。
 */
@Component
public class SettlePriceResolver implements PriceResolver {

  private final PriceSettleItemMapper priceSettleItemMapper;

  public SettlePriceResolver(PriceSettleItemMapper priceSettleItemMapper) {
    this.priceSettleItemMapper = priceSettleItemMapper;
  }

  @Override
  public PriceTypeEnum priceType() {
    return PriceTypeEnum.SETTLE;
  }

  @Override
  public PriceResolveResult resolve(String oaNo, CostRunPartItemDto item, PriceTypeRoute route) {
    String code = item.getPartCode();
    if (!StringUtils.hasText(code)) {
      return PriceResolveResult.miss("partCode 为空，无法查结算价");
    }
    List<PriceSettleItem> rows =
        priceSettleItemMapper.selectList(
            Wrappers.lambdaQuery(PriceSettleItem.class)
                .eq(PriceSettleItem::getMaterialCode, code)
                .orderByDesc(PriceSettleItem::getId)
                .last("LIMIT 1"));
    if (rows.isEmpty()) {
      return PriceResolveResult.miss("lp_price_settle_item 无记录: " + code);
    }
    PriceSettleItem row = rows.get(0);
    BigDecimal price = pickFirstNonNull(
        row.getLinkedSettlePrice(), row.getBaseSettlePrice(), row.getPlannedPrice());
    if (price == null) {
      return PriceResolveResult.miss("lp_price_settle_item 价格列全空: " + code);
    }
    return PriceResolveResult.hit(price, "家用结算价");
  }

  private static BigDecimal pickFirstNonNull(BigDecimal... values) {
    for (BigDecimal v : values) {
      if (v != null) {
        return v;
      }
    }
    return null;
  }
}
