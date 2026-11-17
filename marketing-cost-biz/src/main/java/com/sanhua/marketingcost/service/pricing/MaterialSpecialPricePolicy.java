package com.sanhua.marketingcost.service.pricing;

import java.util.Map;

/** A persistent, exclusive source requirement. Price freshness is independent of source type. */
public record MaterialSpecialPricePolicy(long id, String businessUnitType, String priceOrgCode,
    String materialCode, String materialName, String materialSpec, String materialModel,
    String priceType, String refreshMode, boolean enabled, String reminderText, long version) {
  public static final Map<String,String> TYPES = Map.ofEntries(
      Map.entry("INQUIRY","询价"),Map.entry("FIXED","固定价"),Map.entry("LINKED","联动价"),
      Map.entry("RANGE","区间价"),Map.entry("SETTLE_FIXED","结算固定价"),
      Map.entry("SETTLE_LINKED","结算联动价"));
  public MaterialSpecialPricePolicy {
    if (!TYPES.containsKey(priceType) || !java.util.Set.of("CURRENT_MONTH","LATEST_VALID").contains(refreshMode))
      throw new IllegalArgumentException("请选择有效的限定价格类型和更新方式");
  }
  public boolean currentMonthOnly() { return "CURRENT_MONTH".equals(refreshMode); }
  public String requiredMessage(String month) {
    return materialCode+"：请财务导入"+(currentMonthOnly()?month+"的":"适用的")+TYPES.get(priceType)
        +"数据，完成后继续核算"+(reminderText==null||reminderText.isBlank()?"":"；"+reminderText);
  }
}
