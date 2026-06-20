package com.sanhua.marketingcost.service.pricing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.MakePartPriceCalcRowMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 制造件价格生成结果 Resolver。
 *
 * <p>实时成本里的制造件价格只能消费 {@code lp_make_part_price_calc_row} 生成表最新 OK 结果，
 * 禁止回退 {@code lp_make_part_spec.raw_unit_price / recycle_unit_price} 等旧人工维护字段。
 */
@Service
public class MakePartPriceCalcResolver implements PriceResolver {

  private static final String STATUS_OK = "OK";

  private final MakePartPriceCalcRowMapper calcRowMapper;

  public MakePartPriceCalcResolver(MakePartPriceCalcRowMapper calcRowMapper) {
    this.calcRowMapper = calcRowMapper;
  }

  @Override
  public PriceTypeEnum priceType() {
    return PriceTypeEnum.MAKE;
  }

  @Override
  public PriceResolveResult resolve(String oaNo, CostRunPartItemDto item, PriceTypeRoute route) {
    return resolve(oaNo, item, route, null);
  }

  @Override
  public PriceResolveResult resolve(
      String oaNo, CostRunPartItemDto item, PriceTypeRoute route, CostRunContext context) {
    String parentMaterialNo = item == null ? null : item.getPartCode();
    if (!StringUtils.hasText(parentMaterialNo)) {
      return PriceResolveResult.miss("partCode 为空");
    }
    String parentCode = parentMaterialNo.trim();
    String oaNoValue = trimToNull(oaNo);
    String businessUnitType = trimToNull(context == null ? null : context.getBusinessUnitType());
    if (businessUnitType == null) {
      businessUnitType = trimToNull(BusinessUnitContext.getCurrentBusinessUnitType());
    }
    String pricingMonth = resolvePricingMonth(context);
    if (oaNoValue == null) {
      return PriceResolveResult.miss(missingResultRemark(null, pricingMonth, parentCode));
    }
    if (businessUnitType == null) {
      return PriceResolveResult.miss("缺当前业务单元上下文，无法严格匹配制造件价格生成结果：" + parentCode);
    }

    LocalDateTime priceAsOfTime = monthlyRepricePriceAsOfTime(context);
    MakePartPriceCalcRow latest =
        selectLatestComplete(parentCode, oaNoValue, pricingMonth, businessUnitType, priceAsOfTime);
    if (latest == null) {
      // 这是业务数据缺失，不允许按 0 继续算；由上游标 ERROR 并展示给用户处理生成结果。
      return PriceResolveResult.miss(missingResultRemark(oaNoValue, pricingMonth, parentCode));
    }

    BigDecimal unitPrice = latest.getParentTotalCostPrice();
    if (unitPrice == null) {
      return PriceResolveResult.miss("制造件价格生成结果缺少价格：" + parentCode);
    }
    return new PriceResolveResult(
        unitPrice,
        PriceTypeEnum.MAKE.getDbText(),
        "取自制造件价格生成结果：批次=" + latest.getCalcBatchId()
            + "，价格月份=" + pricingMonth
            + (priceAsOfTime == null ? "" : "，取价时点=" + priceAsOfTime));
  }

  private MakePartPriceCalcRow selectLatestComplete(
      String parentMaterialNo,
      String oaNo,
      String pricingMonth,
      String businessUnitType,
      LocalDateTime priceAsOfTime) {
    // 制造件价格可能受 OA 场景影响，不能回退其他 OA、空 OA 或通用批次。
    LambdaQueryWrapper<MakePartPriceCalcRow> query =
        Wrappers.lambdaQuery(MakePartPriceCalcRow.class)
            .eq(MakePartPriceCalcRow::getParentMaterialNo, parentMaterialNo)
            .eq(MakePartPriceCalcRow::getOaNo, oaNo)
            .eq(MakePartPriceCalcRow::getPricingMonth, pricingMonth)
            .eq(MakePartPriceCalcRow::getBusinessUnitType, businessUnitType)
            .eq(MakePartPriceCalcRow::getStatus, STATUS_OK)
            .eq(MakePartPriceCalcRow::getPriceComplete, true)
            .isNotNull(MakePartPriceCalcRow::getParentTotalCostPrice);
    if (priceAsOfTime != null) {
      // 月度调价重试必须命中同一 price_as_of_time 生成结果，不能读取后续手工刷新出的当前结果。
      query.eq(MakePartPriceCalcRow::getPriceAsOfTime, priceAsOfTime);
    }
    List<MakePartPriceCalcRow> rows =
        calcRowMapper.selectList(
            query.orderByDesc(MakePartPriceCalcRow::getCreatedAt)
                .orderByDesc(MakePartPriceCalcRow::getId)
                .last("LIMIT 1"));
    return rows.isEmpty() ? null : rows.get(0);
  }

  private LocalDateTime monthlyRepricePriceAsOfTime(CostRunContext context) {
    if (context == null || !CostRunContext.SCENE_MONTHLY_REPRICE.equals(context.getScene())) {
      return null;
    }
    return context.getPriceAsOfTime();
  }

  private String missingResultRemark(String oaNo, String pricingMonth, String parentCode) {
    return "缺制造件价格生成结果：OA=" + (oaNo == null ? "" : oaNo)
        + "，月份=" + pricingMonth
        + "，料号=" + parentCode
        + "，请先到“制造件价格生成”页面按 OA 生成并处理异常。";
  }

  private String resolvePricingMonth(CostRunContext context) {
    if (context != null && StringUtils.hasText(context.getPricingMonth())) {
      return context.getPricingMonth().trim();
    }
    return YearMonth.now().toString();
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
