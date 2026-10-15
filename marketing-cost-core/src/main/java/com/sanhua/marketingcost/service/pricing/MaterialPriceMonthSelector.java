package com.sanhua.marketingcost.service.pricing;

import com.sanhua.marketingcost.mapper.MaterialPriceMonthMapper;
import org.springframework.stereotype.Component;

/** 联动和行情区间共用月度边界；整月缺失可沿用，选中月缺料号必须报缺价。 */
@Component
public class MaterialPriceMonthSelector {
  public static final String MATERIAL_MONTH_PRICE_MISSING = "MATERIAL_MONTH_PRICE_MISSING";
  private final MaterialPriceMonthMapper mapper;

  public MaterialPriceMonthSelector(MaterialPriceMonthMapper mapper) {
    this.mapper = mapper;
  }

  public PriceMonthSelection select(String businessUnit, String orgCode, String requestedMonth) {
    if (businessUnit == null || businessUnit.isBlank()) {
      throw new IllegalArgumentException("原稿选月缺少业务单元");
    }
    // 同时检查规范格式；字符串月份的数据库排序依赖 YYYY-MM 契约。
    requestedMonth = FactorPriceScope.month(requestedMonth);
    String source =
        mapper.findLatestMonth(businessUnit, normalizeOrganization(orgCode), requestedMonth);
    return source == null ? null : new PriceMonthSelection(requestedMonth, source);
  }

  public boolean hasRange(
      String businessUnit, String orgCode, String sourceMonth, String materialCode) {
    return mapper.countRangeRules(
            businessUnit, normalizeOrganization(orgCode), sourceMonth, materialCode)
        > 0;
  }

  public static String missingMessage(
      String materialCode, String orgCode, PriceMonthSelection months) {
    return "料号="
        + materialCode
        + "，取价组织="
        + normalizeOrganization(orgCode)
        + "，核算月="
        + months.requestedMonth()
        + "，原稿月份="
        + months.sourceMonth()
        + "：该月份已导入原稿，但未导入此料号，缺价；不沿用更早月份";
  }

  private static String normalizeOrganization(String orgCode) {
    // 旧数据未知组织只能匹配未知组织，不能从业务单元推测为210/220。
    return orgCode == null ? "" : orgCode.trim();
  }
}
