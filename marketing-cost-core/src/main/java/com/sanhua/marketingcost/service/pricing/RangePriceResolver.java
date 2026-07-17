package com.sanhua.marketingcost.service.pricing;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.PriceRangeFactorRule;
import com.sanhua.marketingcost.entity.PriceRangeItem;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.PriceRangeFactorRuleMapper;
import com.sanhua.marketingcost.mapper.PriceRangeItemMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 区间价 Resolver —— 查 lp_price_range_item，按数量段命中单价。
 *
 * <p>制造件原材料/废料取单位价时没有采购数量上下文，调用方会传 partQty=1。
 */
@Component
public class RangePriceResolver implements PriceResolver {
  private static final String RANGE_BASIS_QTY = "QTY";
  private static final String RANGE_BASIS_FACTOR = "FACTOR";

  private final PriceRangeItemMapper priceRangeItemMapper;
  private final PriceRangeFactorRuleMapper factorRuleMapper;
  private final OaFormMapper oaFormMapper;

  public RangePriceResolver(PriceRangeItemMapper priceRangeItemMapper) {
    this(priceRangeItemMapper, null, null);
  }

  @Autowired
  public RangePriceResolver(
      PriceRangeItemMapper priceRangeItemMapper,
      PriceRangeFactorRuleMapper factorRuleMapper,
      OaFormMapper oaFormMapper) {
    this.priceRangeItemMapper = priceRangeItemMapper;
    this.factorRuleMapper = factorRuleMapper;
    this.oaFormMapper = oaFormMapper;
  }

  @Override
  public PriceTypeEnum priceType() {
    return PriceTypeEnum.RANGE;
  }

  @Override
  public PriceResolveResult resolve(String oaNo, CostRunPartItemDto item, PriceTypeRoute route) {
    return resolve(oaNo, item, route, null);
  }

  @Override
  public PriceResolveResult resolve(
      String oaNo, CostRunPartItemDto item, PriceTypeRoute route, CostRunContext context) {
    String code = item == null ? null : item.getPartCode();
    if (!StringUtils.hasText(code)) {
      return PriceResolveResult.miss("partCode 为空，无法查区间价");
    }
    BigDecimal qty = item.getPartQty() == null ? BigDecimal.ONE : item.getPartQty();
    LocalDate priceDate = pricingDate(route, context);
    PriceRangeFactorRule factorRule = findCurrentFactorRule(code.trim(), context);
    if (factorRule != null) {
      return resolveFactorRange(oaNo, code.trim(), factorRule);
    }
    return resolveQtyRange(code.trim(), qty, priceDate);
  }

  private PriceResolveResult resolveFactorRange(
      String oaNo,
      String materialCode,
      PriceRangeFactorRule factorRule) {
    BigDecimal factorValue = resolveFactorValue(oaNo, factorRule);
    if (factorValue == null) {
      return PriceResolveResult.miss(
          "行情因素区间价缺少报价单行情值: " + factorRule.getFactorCode() + ", material=" + materialCode);
    }
    List<PriceRangeItem> rows =
        priceRangeItemMapper.selectList(
            Wrappers.lambdaQuery(PriceRangeItem.class)
                .eq(PriceRangeItem::getRangeBasis, RANGE_BASIS_FACTOR)
                .eq(PriceRangeItem::getFactorRuleId, factorRule.getId())
                .eq(PriceRangeItem::getCurrentFlag, 1)
                .isNotNull(PriceRangeItem::getPriceExclTax)
                .le(PriceRangeItem::getRangeLow, factorValue)
                .ge(PriceRangeItem::getRangeHigh, factorValue)
                .orderByDesc(PriceRangeItem::getId));
    if (rows == null || rows.isEmpty()) {
      return PriceResolveResult.miss(
          "行情因素区间价未命中当前区间: material=" + materialCode
              + ", factor=" + factorRule.getFactorCode()
              + ", value=" + format(factorValue));
    }
    PriceRangeItem row = rows.get(0);
    if (row.getPriceExclTax() == null) {
      return PriceResolveResult.miss(
          "行情因素区间价缺少不含税价: material=" + materialCode
              + ", factor=" + factorRule.getFactorCode());
    }
    return new PriceResolveResult(
        row.getPriceExclTax(),
        "区间价",
        "行情区间命中(" + factorRule.getFactorCode() + "=" + format(factorValue)
            + ",range=" + format(row.getRangeLow()) + "-" + format(row.getRangeHigh())
            + ",field=price_excl_tax)",
        row.getId());
  }

  private PriceResolveResult resolveQtyRange(String materialCode, BigDecimal qty, LocalDate priceDate) {
    var query = Wrappers.lambdaQuery(PriceRangeItem.class)
        .eq(PriceRangeItem::getMaterialCode, materialCode)
        .and(q -> q.eq(PriceRangeItem::getRangeBasis, RANGE_BASIS_QTY)
            .or()
            .isNull(PriceRangeItem::getRangeBasis))
        .and(q -> q.isNotNull(PriceRangeItem::getPriceInclTax)
            .or()
            .isNotNull(PriceRangeItem::getPriceExclTax));
    if (priceDate != null) {
      query.and(q -> q.le(PriceRangeItem::getEffectiveFrom, priceDate)
          .or()
          .isNull(PriceRangeItem::getEffectiveFrom));
      // 日期型有效期统一按闭区间处理，截止日当天仍然有效。
      query.and(q -> q.ge(PriceRangeItem::getEffectiveTo, priceDate)
          .or()
          .isNull(PriceRangeItem::getEffectiveTo));
    }
    List<PriceRangeItem> rows =
        priceRangeItemMapper.selectList(
            query.orderByDesc(PriceRangeItem::getEffectiveFrom)
                .orderByDesc(PriceRangeItem::getId));
    for (PriceRangeItem row : rows) {
      if (matchesRange(row, qty)) {
        BigDecimal price = row.getPriceInclTax() != null ? row.getPriceInclTax() : row.getPriceExclTax();
        String field = row.getPriceInclTax() != null ? "price_incl_tax" : "price_excl_tax";
        return new PriceResolveResult(
            price,
            "区间价",
            "区间命中(" + format(row.getRangeLow()) + "-" + format(row.getRangeHigh())
                + ",qty=" + format(qty) + ",field=" + field + ")",
            row.getId());
      }
    }
    return PriceResolveResult.miss("lp_price_range_item 无有效区间价: " + materialCode);
  }

  private PriceRangeFactorRule findCurrentFactorRule(String materialCode, CostRunContext context) {
    if (factorRuleMapper == null || !StringUtils.hasText(materialCode)) {
      return null;
    }
    var query = Wrappers.lambdaQuery(PriceRangeFactorRule.class)
        .eq(PriceRangeFactorRule::getMaterialCode, materialCode)
        .eq(PriceRangeFactorRule::getCurrentFlag, 1);
    String businessUnitType = context == null ? null : trimToNull(context.getBusinessUnitType());
    if (businessUnitType == null) {
      query.isNull(PriceRangeFactorRule::getBusinessUnitType);
    } else {
      query.eq(PriceRangeFactorRule::getBusinessUnitType, businessUnitType);
    }
    List<PriceRangeFactorRule> rules =
        factorRuleMapper.selectList(query.orderByDesc(PriceRangeFactorRule::getVersionNo)
            .orderByDesc(PriceRangeFactorRule::getId));
    return rules == null || rules.isEmpty() ? null : rules.get(0);
  }

  private BigDecimal resolveFactorValue(String oaNo, PriceRangeFactorRule factorRule) {
    if (oaFormMapper == null || factorRule == null || !StringUtils.hasText(oaNo)) {
      return null;
    }
    OaForm oaForm =
        oaFormMapper.selectOne(
            Wrappers.lambdaQuery(OaForm.class)
                .eq(OaForm::getOaNo, oaNo.trim())
                .last("LIMIT 1"));
    if (oaForm == null) {
      return null;
    }
    String factorCode = trimToNull(factorRule.getFactorCode());
    if (factorCode == null) {
      return null;
    }
    return switch (factorCode.toUpperCase(Locale.ROOT)) {
      case "CU" -> oaForm.getCopperPrice();
      case "ZN" -> oaForm.getZincPrice();
      case "AL" -> oaForm.getAluminumPrice();
      case "GOLD" -> oaForm.getGoldPrice();
      case "SILVER" -> oaForm.getSilverPrice();
      case "SUS304" -> oaForm.getSus304Price();
      case "SUS316", "SUS316L" -> oaForm.getSus316lPrice();
      default -> null;
    };
  }

  private boolean matchesRange(PriceRangeItem row, BigDecimal qty) {
    BigDecimal low = row.getRangeLow();
    BigDecimal high = row.getRangeHigh();
    return (low == null || qty.compareTo(low) >= 0)
        && (high == null || qty.compareTo(high) <= 0);
  }

  private LocalDate pricingDate(PriceTypeRoute route, CostRunContext context) {
    if (context != null && context.getPriceAsOfTime() != null) {
      return context.getPriceAsOfTime().toLocalDate();
    }
    return route == null ? null : route.effectiveFrom();
  }

  private String format(BigDecimal value) {
    return value == null ? "" : value.stripTrailingZeros().toPlainString();
  }

  private String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }
}
