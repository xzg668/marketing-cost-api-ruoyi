package com.sanhua.marketingcost.service.pricing;

import static com.sanhua.marketingcost.service.pricing.MaterialPriceSourceCatalog.*;

import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 每类来源只汇总一次；不采用月价、不生成制造价格、不调用外部接口。 */
@Repository
public class MaterialPriceSourceScanner {
  private final NamedParameterJdbcTemplate jdbc;
  private final MaterialPriceSourcePrices prices;

  public MaterialPriceSourceScanner(JdbcTemplate jdbc, MaterialPriceSourcePrices prices) {
    this.prices = prices;
    this.jdbc = new NamedParameterJdbcTemplate(jdbc);
  }

  private static String normalized(String expression) {
    return "CONVERT(TRIM(" + expression + ") USING utf8mb4) COLLATE utf8mb4_0900_ai_ci";
  }

  // All identifiers and predicates are fixed application code, never request text.
  private static String source(String from, String code, String name, String org,
      String type, String predicate) {
    String table = from.split(" ")[0];
    String model = table.equals("lp_quote_effective_bom_node") ? "p.material_model" : "NULL";
    String spec = switch(table) {
      case "lp_quote_effective_bom_node" -> "p.material_spec";
      case "lp_price_fixed_item","lp_price_linked_item","lp_price_range_factor_rule","lp_price_range_item","lp_settlement_price" -> "p.spec_model";
      case "lp_invoice_price","lp_household_inquiry_price","lp_scrap_price" -> "p.specification";
      case "lp_inquiry_rule" -> "JSON_UNQUOTE(JSON_EXTRACT(p.rule_json,'$.specification'))";
      default -> "p.spec";
    };
    String fixedDates = table.equals("lp_price_fixed_item")
        ? "p.effective_from fixed_from,p.effective_to fixed_to,p.source_kind fixed_kind,"
        : "NULL fixed_from,NULL fixed_to,NULL fixed_kind,";
    return "SELECT DISTINCT " + fixedDates + "p.id source_record_id," + normalized("'" + table + "'") + " source_table," + normalized(code) + " material_code," + normalized(name)
        + " material_name," + normalized(org) + " organization," + normalized(type)
        + " source_type," + normalized(model) + " source_model," + normalized(spec) + " source_spec FROM " + from + " WHERE " + predicate
        + " AND " + code + " IS NOT NULL AND TRIM(" + code + ")<>''"
        + " AND (:code='' OR (:exact=1 AND " + code + "=:code) OR (:exact=0 AND LOCATE(:code," + code + ")>0))"
        + " AND (:organization='' OR " + org + "=:organization)";
  }

  static String sourceQuery() {
    String publicFixed = "p.business_unit_type=:unit AND p.fixed_price IS NOT NULL "
        + "AND (p.source_kind='PUBLIC' OR (p.source_kind='TECH_SUPPLEMENTAL' AND p.technical_publication_status='AVAILABLE')) "
        + "AND (NULLIF(TRIM(p.pricing_month),'') IS NULL OR p.pricing_month<=:month) "
        + "AND p.source_type IN ('PURCHASE','PURCHASE_FIXED')";
    List<String> sources = new ArrayList<>();
    sources.add(source("lp_price_fixed_item p", "p.material_code", "p.material_name", "p.org_code", "'FIXED'", publicFixed));
    sources.add(source("lp_price_linked_item p", "p.material_code", "p.material_name", "p.org_code", "'LINKED'", """
        p.business_unit_type=:unit AND p.deleted=0 AND p.rule_status IN ('LEGACY','PUBLISHED')
        AND NOT EXISTS (SELECT 1 FROM lp_price_linked_item n WHERE n.business_unit_type=p.business_unit_type
          AND n.org_code=p.org_code AND n.pricing_month=p.pricing_month AND n.rule_identity_key=p.rule_identity_key
          AND n.deleted=0 AND n.rule_status IN ('LEGACY','PUBLISHED') AND n.rule_revision>p.rule_revision)
        AND ((p.source_kind='PUBLIC' AND p.pricing_month=(CASE p.org_code WHEN '210' THEN :month210 ELSE :month220 END))
          OR (p.source_kind='TECH_SUPPLEMENTAL' AND p.technical_publication_status='AVAILABLE'
            AND p.pricing_month<=:month AND (p.effective_from IS NULL OR p.effective_from<=:asOf)
            AND (p.effective_to IS NULL OR p.effective_to>=:monthStart)))
        """));
    sources.add(source("lp_price_range_factor_rule p", "p.material_code", "p.material_name", "p.org_code", "'RANGE'", """
        p.business_unit_type=:unit AND p.rule_status IN ('LEGACY','PUBLISHED')
        AND (p.pricing_month=(CASE p.org_code WHEN '210' THEN :month210 ELSE :month220 END)
          OR (p.pricing_month IS NULL AND p.current_flag=1))
        AND EXISTS (SELECT 1 FROM lp_price_range_item band WHERE band.factor_rule_id=p.id)
        """));
    sources.add(source("lp_price_range_item p", "p.material_code", "p.material_name", "p.org_code", "'RANGE'", """
        p.business_unit_type=:unit AND (p.range_basis='QTY' OR p.range_basis IS NULL) AND p.current_flag=1
        AND (p.price_excl_tax IS NOT NULL OR p.price_incl_tax IS NOT NULL)
        AND (p.effective_from IS NULL OR p.effective_from<=:asOf)
        AND (p.effective_to IS NULL OR p.effective_to>=:monthStart)
        """));
    for (String kind : List.of("TIER", "ORDER")) {
      String table = kind.equals("TIER") ? "ods_sy_v_mm_material_jt_price_cg05_df" : "ods_sy_v_mm_material_order_price_cg05_df";
      sources.add(source(table + " p", "p.material_code", "p.material_name",
          "CASE p.company_name WHEN '浙江三花商用制冷有限公司' THEN '210' ELSE '220' END", "'" + kind + "'",
          ":unit='COMMERCIAL' AND p.company_name IN ('浙江三花商用制冷有限公司','浙江三花板换科技有限公司') "
              + "AND p.dt=(SELECT MAX(dt) FROM " + table + " WHERE dt<=:asOf) AND p.price IS NOT NULL "
              + "AND (p.eff_date IS NULL OR p.eff_date<=:asOf) AND (p.exp_date IS NULL OR p.exp_date>=:monthStart)"));
    }
    sources.add(source("lp_invoice_price p", "p.material_code", "p.material_name", "p.org_code", "'INVOICE'", """
        p.business_unit_type=:unit AND p.unit_price IS NOT NULL
        AND p.batch_id=(SELECT MAX(n.batch_id) FROM lp_invoice_price n
          WHERE n.business_unit_type=p.business_unit_type AND n.org_code=p.org_code AND n.material_code=p.material_code)
        """));
    sources.add(source("lp_inquiry_rule p CROSS JOIN organizations o", "p.material_key",
        "JSON_UNQUOTE(JSON_EXTRACT(p.rule_json,'$.materialName'))", "o.organization", "'INQUIRY'", """
        p.business_unit_type=:unit AND p.price_month<=:month
        AND NOT EXISTS (SELECT 1 FROM lp_inquiry_rule n WHERE n.business_unit_type=p.business_unit_type
          AND n.identity_key=p.identity_key AND n.batch_id>p.batch_id AND n.price_month<=:month)
        """));
    sources.add(source("lp_household_inquiry_price p JOIN lp_household_inquiry_import_batch b ON b.id=p.import_batch_id CROSS JOIN organizations o",
        "p.material_code", "p.material_name", "o.organization", "'HOUSEHOLD_INQUIRY'", """
        p.business_unit_type=:unit AND b.status='PUBLISHED' AND b.source_month<=:month AND p.unit_price IS NOT NULL
        AND NOT EXISTS (SELECT 1 FROM lp_household_inquiry_price n
          JOIN lp_household_inquiry_import_batch nb ON nb.id=n.import_batch_id
          WHERE n.business_unit_type=p.business_unit_type AND n.match_key=p.match_key AND nb.status='PUBLISHED'
            AND nb.source_month<=:month AND (nb.source_month>b.source_month OR (nb.source_month=b.source_month AND n.revision_no>p.revision_no)))
        """));
    sources.add(source("lp_settlement_price p CROSS JOIN organizations o", "p.material_code", "p.material_name", "o.organization", "p.price_type", """
        p.business_unit_type=:unit AND p.price_month=:month AND p.active=1
        AND p.price_type IN ('SETTLE_FIXED','SETTLE_LINKED')
        """));
    sources.add(source("lp_scrap_price p CROSS JOIN organizations o", "p.material_code", "p.material_name", "o.organization", "'SCRAP'", """
        p.business_unit_type=:unit AND p.price_month=:month AND p.calc_status='READY' AND p.unit_price IS NOT NULL
        """));
    sources.add(source("lp_quote_effective_bom_node p JOIN lp_quote_costing_workspace w ON w.current_bom_build_batch_id=p.build_batch_id",
        "p.material_code", "p.material_name", "p.price_org_code", "'MAKE'",
        "w.business_unit_type=:unit AND w.period_month=:month AND p.cost_period_month=:month "
            + "AND (p.effective_material_shape IN ('MANUFACTURE','制造件','自制件') OR p.source_material_shape IN ('MANUFACTURE','制造件','自制件'))"));
    return """
        WITH organizations AS (SELECT '210' organization UNION ALL SELECT '220'),
        latest_preparations AS (
          SELECT p.* FROM lp_price_prepare_item p JOIN (
            SELECT material_code,price_org_code,MAX(id) id FROM lp_price_prepare_item
            WHERE business_unit_type=:unit AND period_month=:month AND current_flag=1
            GROUP BY material_code,price_org_code
          ) latest ON latest.id=p.id
        ), sources AS (
        """ + String.join("\nUNION ALL\n", sources) + """
        ), grouped AS (
          SELECT material_code,organization,MAX(material_name) material_name,
            GROUP_CONCAT(DISTINCT source_type ORDER BY source_type) source_types,
            COUNT(DISTINCT source_type) source_count
          FROM sources WHERE organization IN ('210','220') GROUP BY material_code,organization
          HAVING (:multipleOnly=0 OR COUNT(DISTINCT source_type)>1)
        )
        """;
  }

  private record FixedReference(Reference reference, LocalDate from, LocalDate to) {}

  public List<Candidate> scan(Map<String,Object> params) {
    var references = new ArrayList<Reference>();
    var fixed = new LinkedHashMap<String,List<FixedReference>>();
    var candidates = new LinkedHashMap<Key,Candidate>();
    jdbc.query(sourceQuery() + """
        SELECT s.*,COALESCE(NULLIF(m.material_name,''),s.material_name) display_name,
          COALESCE(NULLIF(s.source_model,''),m.material_model) material_model,COALESCE(NULLIF(s.source_spec,''),m.material_spec) material_spec
        FROM sources s JOIN grouped g ON g.material_code=s.material_code AND g.organization=s.organization
        LEFT JOIN lp_material_master_raw m ON m.material_code=s.material_code AND m.active_flag=1
          AND m.organization_code=CASE s.organization WHEN '210' THEN 'COMMERCIAL' ELSE 'PLATE' END
        ORDER BY s.material_code,s.organization,s.source_type,s.source_record_id
        """, params, (org.springframework.jdbc.core.RowCallbackHandler) row -> {
      var key = new Key(row.getString("material_code"),row.getString("organization"),row.getString("source_type"));
      var reference = new Reference(key,row.getString("source_table"),row.getLong("source_record_id"));
      if (reference.table().equals("lp_price_fixed_item")) {
        var from = row.getDate("fixed_from");
        var to = row.getDate("fixed_to");
        fixed.computeIfAbsent(key.organization()+":"+key.materialCode()+":"+row.getString("fixed_kind"),ignored -> new ArrayList<>())
            .add(new FixedReference(reference,from==null ? null : from.toLocalDate(),to==null ? null : to.toLocalDate()));
      } else references.add(reference);
      candidates.putIfAbsent(key,new Candidate(key,row.getString("display_name"),row.getString("material_model"),
          row.getString("material_spec"),key.organization().equals("210") ? "浙江三花商用制冷有限公司" : "浙江三花板换科技有限公司",
          List.of(),key.sourceType().equals("MAKE") ? "MANUFACTURE" : null));
    });
    LocalDate monthStart = (LocalDate) params.get("monthStart");
    LocalDate monthEnd = (LocalDate) params.get("asOf");
    for (var group : fixed.values()) {
      var current = group.stream().filter(r -> (r.from()==null || !r.from().isAfter(monthEnd))
          && (r.to()==null || !r.to().isBefore(monthStart))).toList();
      // A monthly catalog includes the month's execution periods. When none exist,
      // use the same latest-period fallback as ordinary fixed-price costing.
      var selected = current.isEmpty()
          ? SupplyPriceDatePolicy.preferCurrentOrLatestPeriod(group,monthEnd,FixedReference::from,FixedReference::to)
          : current;
      selected.forEach(r -> references.add(r.reference()));
    }
    var values = prices.read(references,params);
    return candidates.values().stream().map(c -> new Candidate(c.key(),c.materialName(),c.materialModel(),c.materialSpec(),
        c.organizationName(),values.getOrDefault(c.key(),List.of()),c.autoAttribute())).toList();
  }
}
