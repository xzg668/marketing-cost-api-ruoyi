package com.sanhua.marketingcost.service.jjb;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Finance-facing labels from the captured source; no queries against mutable source tables. */
final class JjbSourceEvidence {
  static Map<String,String> describe(JsonNode evidence) {
    var result=new LinkedHashMap<String,String>();
    var kind=evidence.path("sourceKind").asText();var source=evidence.path("source");
    var selected=source.has("selected")?source.path("selected"):source.has("selectedRule")?source.path("selectedRule"):source;
    result.put("name",Objects.toString(JjbPricePresentation.source(kind),"历史未记录"));
    result.put("table",switch(kind) {
      case "PURCHASE_FIXED", "TECH_FIXED" -> "lp_price_fixed_item";
      case "LINKED", "LINKED_LEGACY" -> "lp_price_linked_item";
      case "RANGE_FACTOR", "RANGE_LEGACY_FACTOR" -> "lp_price_range_factor_rule";
      case "RANGE_QTY" -> "lp_price_range_item";
      case "INVOICE" -> "lp_invoice_price";
      case "INQUIRY" -> "lp_inquiry_rule";
      case "SCRAP_PRICE" -> "lp_scrap_price";
      case "SETTLE_FIXED", "SETTLE_LINKED" -> "lp_settlement_price";
      case "ODS_ORDER" -> "ods_sy_v_mm_material_order_price_cg05_df";
      case "ODS_TIER" -> "ods_sy_v_mm_material_jt_price_cg05_df";
      case "HOUSEHOLD_INQUIRY" -> "lp_household_inquiry_price";
      default -> "见保存的来源记录";
    });
    put(result,"sourcePrice",selected,"fixedPrice","price","priceExclTax","unit_price","unitPrice");
    put(result,"originalMonth",source,"originalMonth","sourceMonth");
    if(!result.containsKey("originalMonth"))put(result,"originalMonth",selected,"pricingMonth","period");
    put(result,"file",selected,"importFileName","file_name");
    put(result,"sheet",selected,"sourceSheetName");
    put(result,"row",selected,"sourceRowNumber","sourceRowNo");
    put(result,"document",selected,"srmDocNo","docNo","documentNo","document_no","source");
    put(result,"orderNo",selected,"orderNo","order_no");
    put(result,"orderLineNo",selected,"orderLineNo","order_line_no");
    put(result,"effectiveFrom",selected,"effectiveFrom","effDate");
    put(result,"effectiveTo",selected,"effectiveTo","expDate");
    var rule=source.path("selectionRule").asText();
    result.put("selectionReason",switch(rule) {
      case "SINGLE", "SINGLE_SUPPLIER" -> "保存时仅该来源有单一供应商价格";
      case "RATIO_MAX" -> "按供货比率确定主供方；并列时取最高价";
      case "SAME_PRICE" -> "各供应商有效价格相同";
      case "SAME_SUPPLIER_HIGH" -> "同一供应商多条有效价格取最高价";
      case "CONFIGURED_HIGH" -> "按供货比率表的取最高价配置";
      case "LATEST_IMPORT_THEN_HIGHEST" -> "最近导入批次的适用发票价格取最高价";
      default -> rule.isBlank()?"历史未记录":rule;
    });
    if(rule.isBlank() && source.path("supplier").hasNonNull("message"))
      result.put("selectionReason",source.path("supplier").path("message").asText());
    var order=source.path("orderSelection");
    if("LATEST_SOURCE_APPROVAL".equals(order.path("rule").asText())) {
      result.put("selectionReason",result.get("selectionReason")+"；同一供应商按 source 价格审批日期取最新，同日取最高单价："
          +order.path("documentNumber").asText()+"（日期 "+order.path("documentDate").asText()
          +"）");
    }
    var ratios=new ArrayList<String>();
    for(var r:source.path("ratios")) {
      var ratio=r.path("supplyRatio");
      ratios.add(r.path("supplierCode").asText("")+" "+r.path("supplierName").asText("")+"："
          +(ratio.isNull() || ratio.isMissingNode()?"未维护":new java.math.BigDecimal(ratio.asText()).multiply(new java.math.BigDecimal("100")).stripTrailingZeros().toPlainString()+"%"));
    }
    if(!ratios.isEmpty())result.put("ratios",String.join("；",ratios));
    if(source.has("selectedPublication")) {
      result.put("effectivePriceTable","lp_finance_effective_price");
      put(result,"effectivePriceId",source.path("selectedPublication"),"id");
      put(result,"effectivePrice",source.path("selectedPublication"),"unitPrice");
    }
    return result;
  }
  private static void put(Map<String,String> result,String key,JsonNode source,String... fields) {
    for(var field:fields)if(!source.path(field).isMissingNode() && !source.path(field).isNull()
        && !source.path(field).asText().isBlank()){result.put(key,source.path(field).asText());return;}
  }
}
