package com.sanhua.marketingcost.service.pricing;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.entity.PriceLinkedCalcItem;
import com.sanhua.marketingcost.enums.LinkedPriceCalcScene;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.PriceLinkedCalcItemMapper;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 联动价 Resolver —— 查 lp_price_linked_calc_item，按 QUOTE + oaNo + itemCode 取最新已计算结果。
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
    return resolveQuote(oaNo, item);
  }

  @Override
  public PriceResolveResult resolve(
      String oaNo, CostRunPartItemDto item, PriceTypeRoute route, CostRunContext context) {
    if (context != null && CostRunContext.SCENE_MONTHLY_REPRICE.equals(context.getScene())) {
      return resolveMonthlyAdjust(item, context);
    }
    return resolveQuote(oaNo, item);
  }

  private PriceResolveResult resolveQuote(String oaNo, CostRunPartItemDto item) {
    String code = item.getPartCode();
    if (!StringUtils.hasText(oaNo) || !StringUtils.hasText(code)) {
      return PriceResolveResult.miss("oaNo 或 partCode 为空，无法查联动价");
    }
    List<PriceLinkedCalcItem> rows =
        priceLinkedCalcItemMapper.selectList(
            Wrappers.lambdaQuery(PriceLinkedCalcItem.class)
                .eq(PriceLinkedCalcItem::getOaNo, oaNo)
                .eq(PriceLinkedCalcItem::getItemCode, code)
                // LPE-02：现有单料号 Resolver 只读正常报价结果；场景化读取由后续入口传上下文。
                .eq(PriceLinkedCalcItem::getCalcScene, LinkedPriceCalcScene.QUOTE.getCode())
                .orderByDesc(PriceLinkedCalcItem::getId)
                .last("LIMIT 1"));
    if (rows.isEmpty() || rows.get(0).getPartUnitPrice() == null) {
      return PriceResolveResult.miss("lp_price_linked_calc_item 无记录: oa=" + oaNo + " code=" + code);
    }
    return PriceResolveResult.hit(rows.get(0).getPartUnitPrice(), "联动价");
  }

  private PriceResolveResult resolveMonthlyAdjust(CostRunPartItemDto item, CostRunContext context) {
    String code = item == null ? null : item.getPartCode();
    if (!StringUtils.hasText(code)
        || !StringUtils.hasText(context.getBusinessUnitType())
        || !StringUtils.hasText(context.getPricingMonth())) {
      return PriceResolveResult.miss("月度调价联动价缺少料号、业务单元或月份");
    }
    var query = Wrappers.lambdaQuery(PriceLinkedCalcItem.class)
        // 月调不能读 OA 锁价场景结果，必须读 MONTHLY_ADJUST 联动价结果。
        .eq(PriceLinkedCalcItem::getCalcScene, LinkedPriceCalcScene.MONTHLY_ADJUST.getCode())
        .eq(PriceLinkedCalcItem::getBusinessUnitType, context.getBusinessUnitType().trim())
        .eq(PriceLinkedCalcItem::getPricingMonth, context.getPricingMonth().trim())
        .eq(PriceLinkedCalcItem::getItemCode, code.trim());
    if (context.getAdjustBatchId() == null) {
      query.isNull(PriceLinkedCalcItem::getAdjustBatchId);
    } else {
      query.eq(PriceLinkedCalcItem::getAdjustBatchId, context.getAdjustBatchId());
    }
    List<PriceLinkedCalcItem> rows =
        priceLinkedCalcItemMapper.selectList(query.orderByDesc(PriceLinkedCalcItem::getId).last("LIMIT 1"));
    if (rows.isEmpty() || rows.get(0).getPartUnitPrice() == null) {
      return PriceResolveResult.miss(
          "MONTHLY_ADJUST 联动价无记录: businessUnit="
              + context.getBusinessUnitType()
              + " pricingMonth="
              + context.getPricingMonth()
              + " code="
              + code
              + " adjustBatchId="
              + context.getAdjustBatchId());
    }
    return PriceResolveResult.hit(rows.get(0).getPartUnitPrice(), "月度调价联动价");
  }
}
