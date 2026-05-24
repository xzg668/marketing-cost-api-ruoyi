package com.sanhua.marketingcost.service.pricing;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.entity.PriceRangeItem;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.PriceRangeItemMapper;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 区间价 Resolver —— 查 lp_price_range_item，按数量段命中单价。
 *
 * <p>制造件原材料/废料取单位价时没有采购数量上下文，调用方会传 partQty=1。
 */
@Component
public class RangePriceResolver implements PriceResolver {

  private final PriceRangeItemMapper priceRangeItemMapper;

  public RangePriceResolver(PriceRangeItemMapper priceRangeItemMapper) {
    this.priceRangeItemMapper = priceRangeItemMapper;
  }

  @Override
  public PriceTypeEnum priceType() {
    return PriceTypeEnum.RANGE;
  }

  @Override
  public PriceResolveResult resolve(String oaNo, CostRunPartItemDto item, PriceTypeRoute route) {
    String code = item == null ? null : item.getPartCode();
    if (!StringUtils.hasText(code)) {
      return PriceResolveResult.miss("partCode 为空，无法查区间价");
    }
    BigDecimal qty = item.getPartQty() == null ? BigDecimal.ONE : item.getPartQty();
    List<PriceRangeItem> rows =
        priceRangeItemMapper.selectList(
            Wrappers.lambdaQuery(PriceRangeItem.class)
                .eq(PriceRangeItem::getMaterialCode, code.trim())
                .and(q -> q.isNotNull(PriceRangeItem::getPriceInclTax)
                    .or()
                    .isNotNull(PriceRangeItem::getPriceExclTax))
                .orderByDesc(PriceRangeItem::getEffectiveFrom)
                .orderByDesc(PriceRangeItem::getId));
    for (PriceRangeItem row : rows) {
      if (matchesRange(row, qty)) {
        BigDecimal price = row.getPriceInclTax() != null ? row.getPriceInclTax() : row.getPriceExclTax();
        String field = row.getPriceInclTax() != null ? "price_incl_tax" : "price_excl_tax";
        return new PriceResolveResult(
            price,
            "区间价",
            "区间命中(" + format(row.getRangeLow()) + "-" + format(row.getRangeHigh())
                + ",qty=" + format(qty) + ",field=" + field + ")");
      }
    }
    return PriceResolveResult.miss("lp_price_range_item 无有效区间价: " + code.trim());
  }

  private boolean matchesRange(PriceRangeItem row, BigDecimal qty) {
    BigDecimal low = row.getRangeLow();
    BigDecimal high = row.getRangeHigh();
    return (low == null || qty.compareTo(low) >= 0)
        && (high == null || qty.compareTo(high) <= 0);
  }

  private String format(BigDecimal value) {
    return value == null ? "" : value.stripTrailingZeros().toPlainString();
  }
}
