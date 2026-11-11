package com.sanhua.marketingcost.service.costing;

import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import java.util.ArrayList;
import java.util.List;

/** 财务缺项说明只列实际阻断项；重量取值经过仍保存在原计算底稿。 */
public final class FinanceCostingMessages {
  private FinanceCostingMessages() {}

  public static String manufacturingGap(MakePartPriceCalcRow row) {
    String parent = text(row.getParentMaterialNo()), child = text(row.getChildMaterialNo());
    String status = text(row.getStatus());
    if ("OK".equals(status)) return "价格已生成";
    if ("MISSING_WEIGHT".equals(status)) {
      var missing = new ArrayList<String>();
      if (row.getGrossWeightG() == null) missing.add("毛重");
      if (row.getNetWeightG() == null) missing.add("净重");
      return "制造件" + parent + "（材料" + child + "）"
          + (missing.isEmpty() ? "重量资料需核对" : "缺少" + String.join("、", missing))
          + "，请补齐重量资料";
    }
    if (List.of("MISSING_RAW_PRICE", "RAW_PRICE_ERROR").contains(status))
      return "材料" + child + "未取得可用价格" + priceError(row.getRemark());
    if (List.of("MISSING_SCRAP_PRICE", "SCRAP_PRICE_ERROR").contains(status))
      return "废料" + text(row.getScrapCode()) + "未取得可用价格" + priceError(row.getRemark());
    if ("MISSING_SCRAP_MAPPING".equals(status))
      return "材料" + child + "未维护对应废料料号，请核实CMS废料映射";
    if ("MISSING_BOM".equals(status)) return "制造件" + parent + "缺少可用BOM材料明细";
    if ("INVALID_PROCESS_TYPE".equals(status)) return "制造件" + parent + "的加工类型未明确，请核实是原料加工还是毛坯加工";
    return "制造件" + parent + "的价格未生成，请核实材料资料";
  }

  public static String priceGap(com.sanhua.marketingcost.entity.MakePartPriceGapItem item) {
    String code = item.getMissingMaterialNo();
    if (code == null || code.isBlank()) code = "SCRAP".equals(item.getMissingPriceRole()) ? item.getScrapCode() : item.getChildMaterialNo();
    return ("SCRAP".equals(item.getMissingPriceRole()) ? "废料" : "材料") + text(code)
        + "本次未取得可用价格，请核对对应价格来源";
  }

  private static String priceError(String message) {
    if (message == null) return "，请查看价格来源核对";
    var matcher = java.util.regex.Pattern.compile("(?:原材料|废料)取价异常\\([^)]*\\)[:：]\\s*([^；]+)").matcher(message);
    if (matcher.find() && !matcher.group(1).contains("当前料号没有技术补录办理来源")) return "：" + matcher.group(1);
    return "，请查看价格来源核对";
  }
  private static String text(String value) { return value == null ? "未标识料号" : value; }
}
