package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 草稿保留未填项；本人提交时校验价格口径，manual 的可用性另由财务发布决定。 */
public final class TechnicalDataPriceRules {
  private TechnicalDataPriceRules() {}

  public static List<String> validate(PriceItem item) {
    List<String> issues = new ArrayList<>();
    if (item == null) return List.of("尚未填写价格");
    if (!Set.of("FIXED", "REFERENCE", "MANUAL").contains(text(item.entryMode()))) return List.of("请选择一种补价方式");
    if (text(item.unit()).isEmpty() || !"CNY".equals(item.currency())) issues.add("需求单位或币种尚未核实");
    if ("FIXED".equals(item.entryMode())) {
      if (item.unitPrice() == null || item.unitPrice().signum() <= 0) issues.add("不含税单价必须大于 0");
      if (item.formula() != null || item.parameters() != null || item.reference() != null) issues.add("固定价不能携带公式或参考参数");
    } else {
      if (text(item.formula()).isEmpty()) issues.add("联动公式不能为空");
      if (item.unitPrice() != null) issues.add("公式方式不能携带固定单价");
      if ("REFERENCE".equals(item.entryMode()) && item.reference() == null) issues.add("请选择参考公式");
      if ("MANUAL".equals(item.entryMode()) && item.reference() != null) issues.add("自行公式不能携带参考来源");
      var p = item.parameters();
      if (p != null) {
        nonnegative(p.blankWeight(), "下料重", issues); nonnegative(p.netWeight(), "净重", issues);
        nonnegative(p.processFee(), "加工费", issues); nonnegative(p.agentFee(), "代理费", issues);
        if (p.blankWeight() != null && p.netWeight() != null && p.netWeight().compareTo(p.blankWeight()) > 0) issues.add("净重不能大于下料重");
        if ((p.blankWeight() != null || p.netWeight() != null) && !Set.of("克", "千克").contains(text(p.weightUnit()))) issues.add("请选择重量单位");
        if ((p.processFee() != null || p.agentFee() != null) && !Set.of("元/只", "元/件", "元/千克").contains(text(p.feeUnit()))) issues.add("请选择费用单位");
      }
    }
    return List.copyOf(issues);
  }

  private static void nonnegative(BigDecimal value, String name, List<String> issues) {
    if (value != null && value.signum() < 0) issues.add(name + "不能小于 0");
  }
  private static String text(String value) { return value == null ? "" : value.trim(); }
}
