package com.sanhua.marketingcost.service.pricing.monthly;

import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionRetryPolicy.Failure;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Resolves only a BOM row without a material code, using the price source's specification. */
@Component
public final class BomSpecificationPriceIdentity {
  private final JdbcTemplate jdbc;
  public BomSpecificationPriceIdentity(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public record Identity(String materialCode,String unit) {}

  public Identity resolve(MonthlyMaterialPrices.Context context, String organization, String specification) {
    if (specification == null || specification.isBlank())
      throw new MonthlyPriceProblem(Failure.MISSING, "BOM未填写料号或规格，无法匹配价格");
    String spec = specification.trim();
    String business = context.businessUnitType(), month = context.month().toString();
    String sourceMonth=jdbc.queryForObject("SELECT MAX(pricing_month) FROM (SELECT pricing_month FROM lp_price_linked_item WHERE business_unit_type=? AND org_code=? AND source_kind='PUBLIC' AND deleted=0 AND rule_status IN ('LEGACY','PUBLISHED') AND pricing_month<=? UNION ALL SELECT pricing_month FROM lp_price_range_factor_rule WHERE business_unit_type=? AND org_code=? AND rule_status IN ('LEGACY','PUBLISHED','VOIDED') AND pricing_month<=?) available_months",String.class,business,organization,month,business,organization,month);
    if(sourceMonth==null)sourceMonth=month;
    // These queries identify an existing material; ordinary adapters still select its current
    // price and supplier. Neither the original BOM nor any source row is updated.
    var queries = List.of(
        new Query("SELECT material_code,specification,pricing_unit AS unit FROM lp_material_monthly_price WHERE business_unit_type=? AND pricing_month=? AND (organization_mask & ?)!=0 AND specification=? AND "+com.sanhua.marketingcost.mapper.monthly.MonthlyPriceStorageMapper.CURRENT.replace("&amp;","&").replace("&lt;","<"),
            List.of(business, month, "210".equals(organization) ? 1 : 2, spec)),
        new Query("SELECT p.material_code,p.specification,b.pricing_unit AS unit FROM lp_household_inquiry_price p JOIN lp_household_inquiry_import_batch b ON b.id=p.import_batch_id WHERE p.business_unit_type=? AND b.status='PUBLISHED' AND b.source_month<=? AND p.specification=?",
            List.of(business,month,spec)),
        new Query("SELECT material_code,spec_model AS specification,unit FROM lp_price_linked_item WHERE business_unit_type=? AND org_code=? AND deleted=0 AND rule_status IN ('LEGACY','PUBLISHED') AND ((source_kind='PUBLIC' AND pricing_month=?) OR (source_kind='TECH_SUPPLEMENTAL' AND technical_publication_status='AVAILABLE' AND pricing_month<=?)) AND spec_model=?",
            List.of(business, organization, sourceMonth, month, spec)),
        new Query("SELECT material_code,spec_model AS specification,material_unit AS unit FROM lp_settlement_price WHERE business_unit_type=? AND active=1 AND voided_at IS NULL AND price_month<=? AND spec_model=?",
            List.of(business, month, spec)),
        new Query("SELECT material_code,spec_model AS specification,price_unit AS unit FROM lp_price_range_factor_rule p WHERE business_unit_type=? AND org_code=? AND (pricing_month IS NULL OR pricing_month=?) AND rule_status IN ('LEGACY','PUBLISHED') AND spec_model=? AND "+com.sanhua.marketingcost.mapper.PriceRangeFactorRuleMapper.NOT_MASKED_BY_WITHDRAWAL,
            List.of(business, organization, sourceMonth, spec)),
        new Query("SELECT material_code,spec_model AS specification,unit FROM lp_price_range_item WHERE business_unit_type=? AND org_code=? AND spec_model=? AND (range_basis IS NULL OR range_basis='QTY')",
            List.of(business, organization, spec)),
        new Query("SELECT material_code,spec_model AS specification,unit FROM lp_price_fixed_item WHERE business_unit_type=? AND org_code=? AND fixed_price IS NOT NULL AND spec_model=?",
            List.of(business, organization, spec)),
        new Query("SELECT material_code,spec AS specification,unit FROM ods_sy_v_mm_material_jt_price_cg05_df WHERE company_name=? AND dt=(SELECT MAX(dt) FROM ods_sy_v_mm_material_jt_price_cg05_df) AND spec=?",
            List.of(company(organization), spec)),
        new Query("SELECT material_code,specification,pricing_unit AS unit FROM lp_invoice_price p WHERE business_unit_type=? AND org_code=? AND specification=? AND p.row_voided=0 AND EXISTS (SELECT 1 FROM lp_invoice_import_batch b WHERE b.id=p.batch_id AND b.voided_at IS NULL) AND p.batch_id=(SELECT MAX(n.batch_id) FROM lp_invoice_price n WHERE n.business_unit_type=p.business_unit_type AND n.org_code=p.org_code AND n.material_code=p.material_code)",
            List.of(business, organization, spec)),
        new Query("SELECT material_code,spec AS specification,unit FROM ods_sy_v_mm_material_order_price_cg05_df WHERE company_name=? AND dt=(SELECT MAX(dt) FROM ods_sy_v_mm_material_order_price_cg05_df) AND spec=?",
            List.of(company(organization), spec)),
        new Query("SELECT JSON_UNQUOTE(JSON_EXTRACT(JSON_UNQUOTE(JSON_EXTRACT(rule_json,'$.document')),'$.materialCode')) AS material_code,JSON_UNQUOTE(JSON_EXTRACT(JSON_UNQUOTE(JSON_EXTRACT(rule_json,'$.document')),'$.specification')) AS specification,JSON_UNQUOTE(JSON_EXTRACT(JSON_UNQUOTE(JSON_EXTRACT(rule_json,'$.document')),'$.unit')) AS unit FROM lp_inquiry_rule r WHERE business_unit_type=? AND price_month<=? AND specification_key=? AND r.row_voided=0 AND EXISTS (SELECT 1 FROM lp_inquiry_import_batch b WHERE b.id=r.batch_id AND b.status<>'VOIDED')             AND NOT EXISTS (SELECT 1 FROM lp_inquiry_rule newer WHERE newer.business_unit_type=r.business_unit_type               AND newer.identity_key=r.identity_key AND newer.batch_id>r.batch_id AND newer.price_month<=?)",
            List.of(business,month,com.sanhua.marketingcost.service.pricing.inquiry.InquiryNames.normalize(spec),month)));
    for (var query : queries) {
      var codes = new TreeSet<String>();
      var units = new TreeSet<String>();
      for (var row : jdbc.queryForList(query.sql(), query.arguments().toArray())) {
        // SQL collation must not turn different specification text into an exact match.
        if (!spec.equals(Objects.toString(row.get("specification"), "").trim())) continue;
        String code = Objects.toString(row.get("material_code"), "").trim();
        codes.add(code);
        String unit=Objects.toString(row.get("unit"),"").trim();
        if(!unit.isEmpty())units.add(unit);
      }
      if (codes.size() > 1)
        throw new MonthlyPriceProblem(Failure.INVALID_INPUT,
            "BOM规格“" + spec + "”对应多个价格料号：" + String.join("、", codes));
      if (!codes.isEmpty()) return new Identity(codes.first(),units.isEmpty()?null:units.first());
    }
    throw new MonthlyPriceProblem(Failure.MISSING, "BOM规格“" + spec + "”未找到对应价格来源");
  }

  private static String company(String organization) {
    return switch (organization) {
      case "210" -> "浙江三花商用制冷有限公司";
      case "220" -> "浙江三花板换科技有限公司";
      default -> throw new IllegalArgumentException("未知取价组织");
    };
  }
  private record Query(String sql, List<?> arguments) {}
}
