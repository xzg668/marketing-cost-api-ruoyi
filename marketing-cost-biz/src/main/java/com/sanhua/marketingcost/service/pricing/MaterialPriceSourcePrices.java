package com.sanhua.marketingcost.service.pricing;

import static com.sanhua.marketingcost.service.pricing.MaterialPriceSourceCatalog.*;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只读取来源保存的金额，保留供应商、税口径和价格条件，不在清单更新时重新算价。 */
@Repository
public class MaterialPriceSourcePrices {
  private final NamedParameterJdbcTemplate jdbc;
  public MaterialPriceSourcePrices(JdbcTemplate jdbc) { this.jdbc=new NamedParameterJdbcTemplate(jdbc); }
  private record RecordKey(String table,long id) {}

  public Map<Key,List<Price>> read(List<Reference> references, Map<String,Object> scope) {
    var ids=new LinkedHashMap<String,Set<Long>>();
    references.forEach(r -> ids.computeIfAbsent(r.table(),ignored -> new LinkedHashSet<>()).add(r.id()));
    var saved=new HashMap<RecordKey,List<Price>>();
    for (var table:ids.entrySet()) {
      var all=new ArrayList<>(table.getValue());
      for (int start=0;start<all.size();start+=256) {
        var params=new HashMap<String,Object>(scope); params.put("ids",all.subList(start,Math.min(start+256,all.size())));
        jdbc.query(query(table.getKey()),params,(org.springframework.jdbc.core.RowCallbackHandler) r -> {
          var amount=r.getBigDecimal("price");
          var price=new Price(amount==null ? null : amount.stripTrailingZeros().toPlainString(),r.getString("unit"),
              r.getString("currency"),r.getString("tax_basis"),r.getString("supplier_code"),r.getString("supplier_name"),
              r.getString("condition_text"),amount==null ? "待生成" : "已有价格");
          saved.computeIfAbsent(new RecordKey(table.getKey(),r.getLong("record_id")),ignored -> new ArrayList<>()).add(price);
        });
      }
    }
    var result=new LinkedHashMap<Key,LinkedHashSet<Price>>();
    for (var r:references) result.computeIfAbsent(r.key(),ignored -> new LinkedHashSet<>())
        .addAll(saved.getOrDefault(new RecordKey(r.table(),r.id()),List.of()));
    var answer=new LinkedHashMap<Key,List<Price>>();
    result.forEach((key,values) -> answer.put(key,List.copyOf(values)));
    return answer;
  }

  private static String select(String from,String price,String unit,String currency,String tax,String code,String name,String condition) {
    return "SELECT p.id record_id,"+price+" price,"+unit+" unit,"+currency+" currency,"+tax+" tax_basis,"
        +code+" supplier_code,"+name+" supplier_name,"+condition+" condition_text FROM "+from+" WHERE p.id IN (:ids)";
  }
  private static String financial() {
    return """
        LEFT JOIN lp_factor_upload_batch b ON b.id=(SELECT h.id FROM lp_factor_upload_batch h
          WHERE h.business_unit_type=p.business_unit_type AND h.price_org_code=p.org_code AND h.import_type='FINANCE_BASE'
            AND h.status='PUBLISHED' AND h.price_month<=:month ORDER BY h.price_month DESC,h.publication_revision DESC,h.id DESC LIMIT 1)
        LEFT JOIN lp_finance_effective_price f ON f.business_unit_type=p.business_unit_type AND f.org_code=p.org_code
          AND f.original_id=p.id AND f.price_type=:priceType AND f.original_revision=COALESCE(p.rule_revision,1)
          AND f.finance_batch_id=b.id AND f.scheme_code=b.default_scheme_code AND f.calculator_version='FINANCE_EFFECTIVE_V1'
        """;
  }
  /** Costing rows are rebuilt on shape changes; saved preparation retains the original organization. */
  static String manufacturedCalculations() {
    return """
        SELECT p.*, CONVERT(COALESCE(c.price_org_code,(
          SELECT i.price_org_code FROM lp_price_prepare_item i
          WHERE i.bom_row_id=p.source_costing_row_id AND i.business_unit_type COLLATE utf8mb4_0900_ai_ci=p.business_unit_type
            AND i.period_month COLLATE utf8mb4_0900_ai_ci=p.pricing_month AND i.material_code COLLATE utf8mb4_0900_ai_ci=p.parent_material_no
            AND i.result_ref_type='MAKE_PART_PRICE' AND i.status='READY'
          ORDER BY i.id DESC LIMIT 1)) USING utf8mb4) COLLATE utf8mb4_0900_ai_ci source_price_org_code, c.unit source_unit
        FROM lp_make_part_price_calc_row p LEFT JOIN lp_bom_costing_row c ON c.id=p.source_costing_row_id
        WHERE p.business_unit_type=:unit AND p.pricing_month=:month
        """;
  }

  private static String query(String table) {
    return switch(table) {
      case "lp_price_fixed_item" -> select(table+" p","p.fixed_price","p.unit","'CNY'",
          "CASE p.tax_included WHEN 1 THEN 'INCLUDED' WHEN 0 THEN 'EXCLUDED' ELSE 'UNKNOWN' END",
          "p.supplier_code","p.supplier_name","CONCAT('生效：',COALESCE(CAST(p.effective_from AS CHAR),''),'～',COALESCE(CAST(p.effective_to AS CHAR),''))");
      case "lp_price_linked_item" -> select(table+" p "+financial().replace(":priceType","'LINKED'"),"f.unit_price","COALESCE(f.unit,p.unit)","'CNY'","'EXCLUDED'",
          "p.supplier_code","p.supplier_name","CONCAT(COALESCE(f.scheme_code,''),' ',COALESCE(f.condition_code,''))");
      case "lp_price_range_factor_rule" -> select(table+" p "+financial().replace(":priceType","'RANGE'").replace("p.rule_revision","p.version_no"),
          "f.unit_price","COALESCE(f.unit,p.price_unit)","'CNY'","'EXCLUDED'","p.supplier_code","p.supplier_name",
          "CONCAT(COALESCE(f.scheme_code,''),' ',COALESCE(f.condition_code,''))");
      case "lp_price_range_item" -> select(table+" p","COALESCE(p.price_excl_tax,p.price_incl_tax)","p.unit","'CNY'",
          "CASE WHEN p.price_excl_tax IS NOT NULL THEN 'EXCLUDED' ELSE 'INCLUDED' END","p.supplier_code","p.supplier_name",
          "CONCAT('数量：',COALESCE(CAST(p.range_low AS CHAR),''),'～',COALESCE(CAST(p.range_high AS CHAR),''))");
      case "ods_sy_v_mm_material_order_price_cg05_df","ods_sy_v_mm_material_jt_price_cg05_df" -> select(table+" p","p.price","p.unit","'CNY'","'EXCLUDED'",
          "p.supply_code","p.supply_name",table.contains("_jt_") ? "CONCAT('年用量：',COALESCE(p.jieti_down,''),'～',COALESCE(p.jieti_up,''))" : "CONCAT('订单：',COALESCE(p.order_no,''))");
      case "lp_invoice_price" -> select(table+" p","p.unit_price","p.pricing_unit","p.currency","'EXCLUDED'","p.supplier_code","p.supplier_name","CONCAT('单据：',p.document_no)");
      case "lp_inquiry_rule" -> select(table+" p LEFT JOIN lp_inquiry_effective_price f ON f.id=(SELECT n.id FROM lp_inquiry_effective_price n WHERE n.rule_id=p.id AND n.business_unit_type=p.business_unit_type AND n.price_month=:month ORDER BY n.id DESC LIMIT 1)",
          "CASE WHEN JSON_UNQUOTE(JSON_EXTRACT(p.rule_json,'$.priceType'))='固定价' THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(p.rule_json,'$.originalPrice')) AS DECIMAL(38,18)) WHEN f.status='READY' THEN f.unit_price END",
          "JSON_UNQUOTE(JSON_EXTRACT(p.rule_json,'$.unit'))","'CNY'","'EXCLUDED'","NULL","JSON_UNQUOTE(JSON_EXTRACT(p.rule_json,'$.supplierName'))","JSON_UNQUOTE(JSON_EXTRACT(p.rule_json,'$.priceType'))");
      case "lp_household_inquiry_price" -> select(table+" p JOIN lp_household_inquiry_import_batch b ON b.id=p.import_batch_id","p.unit_price","b.pricing_unit","b.currency","b.tax_basis","NULL","NULL","'家用询价'");
      case "lp_settlement_price" -> select(table+" p LEFT JOIN lp_settlement_effective_price f ON f.id=(SELECT n.id FROM lp_settlement_effective_price n WHERE n.rule_id=p.id AND n.business_unit_type=p.business_unit_type AND n.effective_month<=:month ORDER BY n.effective_month DESC,n.revision_no DESC LIMIT 1)",
          "f.unit_price","p.material_unit","'CNY'","'EXCLUDED'","NULL","NULL","COALESCE(f.price_mode,'未有已生成结算价')");
      case "lp_scrap_price" -> select(table+" p","p.unit_price","p.unit","p.currency","CASE p.tax_included WHEN 1 THEN 'INCLUDED' ELSE 'EXCLUDED' END","p.supplier_code","p.supplier_name","p.price_type");
      case "lp_make_part_price_calc_row" -> "WITH make_calculations AS ("+manufacturedCalculations()+") "+select(
          "make_calculations p LEFT JOIN lp_material_master_raw m ON m.material_code COLLATE utf8mb4_0900_ai_ci=p.parent_material_no AND m.active_flag=1"
          +" AND m.organization_code=CASE p.source_price_org_code WHEN '210' THEN 'COMMERCIAL' WHEN '220' THEN 'PLATE' END",
          "p.parent_total_cost_price","COALESCE(p.source_unit,m.unit)","'CNY'","'EXCLUDED'","NULL","NULL","CONCAT('制造件 ',p.oa_no,' / ',p.calc_batch_id)");
      case "lp_quote_bom_monthly_snapshot_detail" -> select(table+" p JOIN lp_quote_bom_monthly_snapshot s ON s.id=p.monthly_snapshot_id", "NULL", "NULL", "'CNY'", "'EXCLUDED'", "NULL", "NULL",
          "COALESCE((SELECT g.message FROM lp_price_prepare_gap g WHERE g.business_unit_type=:unit AND g.period_month=:month"
          +" AND g.oa_form_item_id=s.source_oa_form_item_id AND g.material_code=JSON_UNQUOTE(JSON_EXTRACT(p.raw_node_json,'$.materialCode'))"
          +" AND g.item_type='MAKE_PART' AND g.current_flag=1 ORDER BY g.id DESC LIMIT 1),"
          +"'自制价格尚未算出；继续自制需检查重量、原材料及废料价格等核算资料')");
      case "lp_quote_effective_bom_node" -> select(table+" p",
          "NULL","p.material_unit","'CNY'","'EXCLUDED'","NULL","NULL","'计价BOM自制来源；金额以实际制造件计算结果为准'");
      default -> throw new IllegalArgumentException("未知来源表："+table);
    };
  }
}
