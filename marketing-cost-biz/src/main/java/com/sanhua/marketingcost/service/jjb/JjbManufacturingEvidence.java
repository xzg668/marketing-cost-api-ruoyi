package com.sanhua.marketingcost.service.jjb;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explains captured MAKE_PART_V1 inputs; never reads current prices or changes saved costs. */
final class JjbManufacturingEvidence {
  private JjbManufacturingEvidence() {}

  static Map<String,String> describe(JsonNode row) {
    var result=new LinkedHashMap<String,String>();
    for (var field: new String[]{"childMaterialNo", "childMaterialName", "stockUnit", "grossWeightG",
        "netWeightG", "qtyPerParent", "recoveredScrapWeightG", "rawUnitPrice", "scrapCode", "scrapName", "scrapUnitPrice", "costPrice",
        "parentTotalCostPrice", "rawPriceType", "scrapPriceType", "remark"}) {
      if (!row.path(field).isMissingNode() && !row.path(field).isNull()) result.put(field,row.path(field).asText());
    }
    var type=row.path("itemProcessType").asText("");
    result.put("processType",type);
    String grossSource = grossWeightSource(type, row.path("remark").asText(""));
    if (grossSource != null) result.put("grossWeightSource", grossSource);
    if (com.sanhua.marketingcost.service.MakePartConsumption.nonWeightRaw(type, row.path("stockUnit").asText(null))) {
      var quantity = decimal(row,"qtyPerParent");
      var raw = decimal(row,"rawUnitPrice"); var scrap = decimal(row,"scrapUnitPrice");
      var recovered = decimal(row,"recoveredScrapWeightG");
      if (recovered == null && decimal(row,"grossWeightG") != null && decimal(row,"netWeightG") != null)
        recovered = decimal(row,"grossWeightG").subtract(decimal(row,"netWeightG"));
      if (row.path("noScrapConfirmed").asBoolean(false)) recovered = BigDecimal.ZERO;
      result.put("rawPriceUnit", "元/" + row.path("stockUnit").asText());
      result.put("formula", "BOM用量 × 原料单价 − 副产品废料重 ÷ 1000 × 废料单价");
      if (quantity != null && raw != null && scrap != null && recovered != null) {
        var scrapKg = recovered.movePointLeft(3);
        result.put("scrapWeightKg", format(scrapKg));
        result.put("materialAmount", format(quantity.multiply(raw)));
        result.put("scrapDeduction", format(scrapKg.multiply(scrap)));
        result.put("substitution", format(quantity) + " × " + format(raw) + " − " + format(scrapKg)
            + " × " + format(scrap) + " = " + result.getOrDefault("costPrice","历史未记录") + " 元/件（保存结果）");
      } else result.put("substitution", "历史输入不完整，保留原保存单价");
      return result;
    }
    result.put("rawPriceUnit","原材料加工".equals(type)?"元/kg":"毛坯加工".equals(type)?"元/件":"历史未记录");
    if (!"毛坯加工".equals(type) && !"原材料加工".equals(type)) return result;
    var gross=decimal(row,"grossWeightG"); var net=decimal(row,"netWeightG");
    var raw=decimal(row,"rawUnitPrice"); var scrap=decimal(row,"scrapUnitPrice");
    result.put("formula","毛坯加工".equals(type)
        ? "毛坯单价 − (毛重 − 净重) ÷ 1000 × 废料单价"
        : "毛重 ÷ 1000 × 原材料单价 − (毛重 − 净重) ÷ 1000 × 废料单价");
    if (gross==null || net==null || raw==null || scrap==null) {
      result.put("substitution","历史输入不完整，保留原保存单价"); return result;
    }
    var scrapWeight=gross.subtract(net).movePointLeft(3);
    result.put("scrapWeightKg",format(scrapWeight));
    result.put("materialAmount",format("毛坯加工".equals(type)?raw:gross.movePointLeft(3).multiply(raw)));
    result.put("scrapDeduction",format(scrapWeight.multiply(scrap)));
    result.put("substitution",("毛坯加工".equals(type)?format(raw):format(gross)+" ÷ 1000 × "+format(raw))
        +" − ("+format(gross)+" − "+format(net)+") ÷ 1000 × "+format(scrap)
        +" = "+result.getOrDefault("costPrice","历史未记录")+" 元/件（保存结果，保留 8 位小数）");
    return result;
  }
  static String grossWeightSource(String type, String remark) {
    if (!"毛坯加工".equals(type)) return null;
    if (remark.contains("毛重来源=SRM联动价表；")) return "SRM联动价表";
    if (remark.contains("毛重来源=SRM区间价表；")) return "SRM区间价表";
    if (remark.contains("毛重取U9 BOM用量")) return "U9 BOM用量";
    if (remark.contains("毛重按本件净重加U9副产品废料重反算"))
      return remark.contains("净重取父子材料净重配置")
          ? "父子净重配置＋U9副产品重量反算" : "U9理论净重＋副产品重量反算";
    if (remark.contains("毛重按本件净重加0g废料重反算"))
      return "U9理论净重＋确认无废料";
    return null;
  }

  private static BigDecimal decimal(JsonNode row,String field) {
    var value=row.path(field);
    if(value.isMissingNode() || value.isNull() || value.asText().isBlank()) return null;
    try {return new BigDecimal(value.asText());} catch(NumberFormatException ignored) {return null;}
  }
  private static String format(BigDecimal value) {return value.stripTrailingZeros().toPlainString();}
}
