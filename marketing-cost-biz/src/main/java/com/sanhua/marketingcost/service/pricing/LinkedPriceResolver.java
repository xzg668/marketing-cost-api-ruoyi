package com.sanhua.marketingcost.service.pricing;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.entity.PriceLinkedCalcItem;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.PriceLinkedCalcItemMapper;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 联动价 Resolver —— 查 lp_price_linked_calc_item，按 oaNo + itemCode 取最新已计算结果。
 *
 * <p>对应 Excel"价格来源 = 联动价"。当前阶段沿用现有 PriceLinkedCalcServiceImpl 已写入的结果，
 * 公式引擎升级（任务 #6/#7）完成后再切到 TemplateEngine。
 */
@Component
public class LinkedPriceResolver implements PriceResolver {

  private final PriceLinkedCalcItemMapper priceLinkedCalcItemMapper;

  public LinkedPriceResolver(PriceLinkedCalcItemMapper priceLinkedCalcItemMapper) {
    this.priceLinkedCalcItemMapper = priceLinkedCalcItemMapper;
  }

  @Override
  public PriceTypeEnum priceType() {
    return PriceTypeEnum.LINKED;
  }

  @Override
  public PriceResolveResult resolve(String oaNo, CostRunPartItemDto item, PriceTypeRoute route) {
    String code = item.getPartCode();
    if (!StringUtils.hasText(oaNo) || !StringUtils.hasText(code)) {
      return PriceResolveResult.miss("oaNo 或 partCode 为空，无法查联动价");
    }
    List<PriceLinkedCalcItem> rows =
        priceLinkedCalcItemMapper.selectList(
            Wrappers.lambdaQuery(PriceLinkedCalcItem.class)
                .eq(PriceLinkedCalcItem::getOaNo, oaNo)
                .eq(PriceLinkedCalcItem::getItemCode, code)
                .orderByDesc(PriceLinkedCalcItem::getId)
                .last("LIMIT 1"));
    if (rows.isEmpty() || rows.get(0).getPartUnitPrice() == null) {
      return PriceResolveResult.miss("lp_price_linked_calc_item 无记录: oa=" + oaNo + " code=" + code);
    }
    return PriceResolveResult.hit(rows.get(0).getPartUnitPrice(), "联动价");
  }
}
