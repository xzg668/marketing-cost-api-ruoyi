package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.service.CostInputRevisionService;
import com.sanhua.marketingcost.service.EffectiveTechnicalDataException;
import com.sanhua.marketingcost.service.EffectiveTechnicalDataQueryService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class CostInputRevisionServiceImpl implements CostInputRevisionService {

  static final List<String> SOURCE_TABLES =
      List.of(
          "cms_cost_source_effective",
          "lp_department_fund_rate",
          "lp_quality_loss_rate",
          "lp_manufacture_rate",
          "lp_three_expense_rate",
          "lp_other_expense_rate",
          "lp_product_property",
          "lp_product_property_rule",
          "lp_supplier_supply_ratio",
          "lp_bom_byproduct_cost_rule",
          "lp_cost_business_rule");

  static final String SOURCE_CHECKSUM_SQL = "CHECKSUM TABLE " + SOURCE_TABLES.stream()
      .map(table -> "`" + table + "`").collect(java.util.stream.Collectors.joining(","));

  // 这些表按料号定价。其他产品的补价不应使本产品的成本版本失效。
  private static final List<String> MATERIAL_PRICE_TABLES =
      List.of(
          "lp_price_fixed_item",
          "lp_price_linked_item",
          "lp_price_range_item",
          "lp_price_range_factor_rule");
  // 历史业务表与 JSON_TABLE 可能使用不同字符集/排序规则；业务编码统一按精确值去重。
  static final String MATERIAL_CODES_SQL =
      """
      WITH prepared AS (
        SELECT material_code,input_snapshot_json,price_org_code FROM lp_price_prepare_item
        WHERE oa_form_item_id=? AND period_month=? AND current_flag=1
      ), nodes AS (
        SELECT n.material_code,n.price_org_code FROM lp_quote_costing_workspace w
        JOIN lp_quote_effective_bom_node n ON n.build_batch_id=w.current_bom_build_batch_id
        WHERE w.oa_form_item_id=? AND w.period_month=?
      )
      SELECT CONVERT(material_code USING utf8mb4) COLLATE utf8mb4_bin AS material_code
        FROM prepared WHERE material_code IS NOT NULL
      UNION SELECT CONVERT(material_code USING utf8mb4) COLLATE utf8mb4_bin
        FROM nodes WHERE material_code IS NOT NULL
      UNION SELECT CONVERT(c.material_code USING utf8mb4) COLLATE utf8mb4_bin
        FROM prepared p JOIN JSON_TABLE(CAST(p.input_snapshot_json AS JSON),
        '$.components[*]' COLUMNS (material_code VARCHAR(64) PATH '$.materialCode')) c
        WHERE c.material_code IS NOT NULL
      ORDER BY material_code
      """;

  static final String PRIORITY_SCOPES_SQL = """
      WITH prepared AS (
        SELECT material_code,price_org_code,input_snapshot_json FROM lp_price_prepare_item
        WHERE oa_form_item_id=? AND period_month=? AND current_flag=1
      ), nodes AS (
        SELECT n.material_code,n.price_org_code FROM lp_quote_costing_workspace w
        JOIN lp_quote_effective_bom_node n ON n.build_batch_id=w.current_bom_build_batch_id
        WHERE w.oa_form_item_id=? AND w.period_month=?
      )
      SELECT CONVERT(material_code USING utf8mb4) COLLATE utf8mb4_bin AS material_code,
        CONVERT(price_org_code USING utf8mb4) COLLATE utf8mb4_bin AS price_org_code
        FROM prepared WHERE price_org_code IS NOT NULL
      UNION SELECT CONVERT(material_code USING utf8mb4) COLLATE utf8mb4_bin,
        CONVERT(price_org_code USING utf8mb4) COLLATE utf8mb4_bin
        FROM nodes WHERE price_org_code IS NOT NULL
      UNION SELECT CONVERT(c.material_code USING utf8mb4) COLLATE utf8mb4_bin,
        CONVERT(c.price_org_code USING utf8mb4) COLLATE utf8mb4_bin FROM prepared p
        JOIN JSON_TABLE(CAST(p.input_snapshot_json AS JSON),'$.components[*].inputs.pricePriority.scope' COLUMNS (
          material_code VARCHAR(64) PATH '$.materialCode',price_org_code VARCHAR(32) PATH '$.priceOrgCode')) c
        WHERE c.material_code IS NOT NULL AND c.price_org_code IS NOT NULL
      """;

  private final JdbcTemplate jdbcTemplate;
  private final EffectiveTechnicalDataQueryService effectiveTechnicalDataQueryService;

  @Autowired
  public CostInputRevisionServiceImpl(
      JdbcTemplate jdbcTemplate,
      ObjectProvider<EffectiveTechnicalDataQueryService> technicalDataProvider) {
    this.jdbcTemplate = jdbcTemplate;
    this.effectiveTechnicalDataQueryService = technicalDataProvider.getIfAvailable();
  }

  public CostInputRevisionServiceImpl(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
    this.effectiveTechnicalDataQueryService = null;
  }

  @Override
  public String currentRevision(OaForm form, OaFormItem item) {
    return currentRevision(form, item, form == null ? null : form.getAccountingPeriodMonth());
  }

  @Override
  public String currentRevision(OaForm form, OaFormItem item, String pricingMonth) {
    if (form == null || item == null) {
      throw new IllegalArgumentException("报价表头和产品行不能为空");
    }
    return scopedRevision(
        sourceRevision(),
        form,
        item,
        effectiveTechnicalFingerprint(item, pricingMonth),
        pricingMonth);
  }

  @Override
  public Map<Long, String> currentRevisions(OaForm form, List<OaFormItem> items) {
    return currentRevisions(form, items, form == null ? null : form.getAccountingPeriodMonth());
  }

  @Override
  public Map<Long, String> currentRevisions(
      OaForm form, List<OaFormItem> items, String pricingMonth) {
    String sourceRevision = sourceRevision();
    Map<Long, String> revisions = new LinkedHashMap<>();
    if (items != null) {
      for (OaFormItem item : items) {
        if (item != null && item.getId() != null) {
          try {
            revisions.put(
                item.getId(),
                scopedRevision(
                    sourceRevision,
                    form,
                    item,
                    effectiveTechnicalFingerprint(item, pricingMonth),
                    pricingMonth));
          } catch (EffectiveTechnicalDataException missing) {
            // 不复用该产品的旧成功；worker 逐品执行时记录明确的技术资料缺口。
          }
        }
      }
    }
    return revisions;
  }

  private String sourceRevision() {
    StringBuilder canonical = new StringBuilder(1024);
    var checksums = new LinkedHashMap<String, Object>();
    for (var row : jdbcTemplate.queryForList(SOURCE_CHECKSUM_SQL)) {
      String name = String.valueOf(valueIgnoreCase(row, "Table"));
      checksums.put(name.substring(name.lastIndexOf('.') + 1), valueIgnoreCase(row, "Checksum"));
    }
    for (String table : SOURCE_TABLES) {
      Object checksum = checksums.get(table);
      if (checksum == null) throw new IllegalStateException("公共核算资料校验失败：" + table);
      append(canonical, table, checksum);
    }
    return sha256(canonical.toString());
  }

  private String scopedRevision(
      String sourceRevision,
      OaForm form,
      OaFormItem item,
      String effectiveTechnicalFingerprint,
      String pricingMonth) {
    StringBuilder canonical = new StringBuilder(1024);
    append(
        canonical,
        "pricingCalculator",
        com.sanhua.marketingcost.service.pricing.OriginalPriceCalculationVersion.CURRENT);
    append(canonical, "sources", sourceRevision);
    append(canonical, "materialPrices", materialPriceRevision(item, pricingMonth));
    append(canonical, "pricePriorityPolicy", "MONTH_ORG_V1");
    append(canonical, "pricePriority", priorityRevision(item, pricingMonth));
    append(canonical, "effectiveTechnicalData", effectiveTechnicalFingerprint);
    // 下游退回要求真正生成新成本；只纳入本报价、本月的退回记录，不改旧成功版本。
    Long returned =
        jdbcTemplate.queryForObject(
            "SELECT MAX(id) FROM lp_quote_final_submission WHERE oa_form_id=? AND"
                + " accounting_month=? AND returned_at IS NOT NULL",
            Long.class,
            form.getId(),
            pricingMonth);
    append(canonical, "returnedQuotationSubmission", returned);
    appendForm(canonical, form);
    appendItem(canonical, item);
    return sha256(canonical.toString());
  }

  private String priorityRevision(OaFormItem item, String month) {
    var scopes = jdbcTemplate.queryForList(PRIORITY_SCOPES_SQL, item.getId(), month, item.getId(), month);
    String sql = "SELECT price_org_code,material_code,price_type,manual_priority,priority_updated_by,"
        + "priority_updated_at FROM lp_material_price_type WHERE business_unit_type=? AND period=?"
        + " AND priority_updated_at IS NOT NULL";
    var parameters = new ArrayList<Object>();
    parameters.add(item.getBusinessUnitType());
    parameters.add(month);
    if (!scopes.isEmpty()) {
      sql += " AND (" + String.join(" OR ", Collections.nCopies(scopes.size(), "(material_code=? AND price_org_code=?)")) + ")";
      for (var scope : scopes) {
        parameters.add(valueIgnoreCase(scope, "material_code"));
        parameters.add(valueIgnoreCase(scope, "price_org_code"));
      }
    }
    sql += " ORDER BY price_org_code,material_code,price_type";
    StringBuilder canonical = new StringBuilder();
    for (var row : jdbcTemplate.queryForList(sql, parameters.toArray())) {
      for (String column : new TreeSet<>(row.keySet())) appendPriceValue(canonical, column, row.get(column));
    }
    return sha256(canonical.toString());
  }

  private String materialPriceRevision(OaFormItem item, String pricingMonth) {
    List<String> materials =
        jdbcTemplate.queryForList(
            MATERIAL_CODES_SQL,
            String.class,
            item.getId(),
            pricingMonth,
            item.getId(),
            pricingMonth);
    if (materials.isEmpty()) {
      // 尚未建立本产品价格准备时无法确定涉及的料号，保守地检查整张价格表。
      StringBuilder global = new StringBuilder();
      for (String table : MATERIAL_PRICE_TABLES) {
        List<Map<String, Object>> rows =
            jdbcTemplate.queryForList("CHECKSUM TABLE `" + table + "`");
        append(global, table, rows.isEmpty() ? null : valueIgnoreCase(rows.get(0), "Checksum"));
      }
      append(
          global,
          "financeFactors",
          financePublicationRevision(item.getBusinessUnitType(), pricingMonth, null));
      append(global, "settlementPrices", settlementRevision(item.getBusinessUnitType(), pricingMonth, List.of()));
      return sha256(global.toString());
    }

    StringBuilder canonical = new StringBuilder();
    List<String> distinctMaterials = new ArrayList<>(new TreeSet<>(materials));
    append(canonical, "settlementPrices", settlementRevision(item.getBusinessUnitType(), pricingMonth, distinctMaterials));
    java.util.Set<String> originalOrganizations = new TreeSet<>();
    distinctMaterials.forEach(material -> append(canonical, "material", material));
    String placeholders = String.join(",", Collections.nCopies(distinctMaterials.size(), "?"));
    for (String table : MATERIAL_PRICE_TABLES) {
      append(canonical, "table", table);
      List<Map<String, Object>> rows =
          jdbcTemplate.queryForList(
              "SELECT * FROM `"
                  + table
                  + "` WHERE material_code IN ("
                  + placeholders
                  + ") ORDER BY id",
              distinctMaterials.toArray());
      for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
        Map<String, Object> row = rows.get(rowIndex);
        if ((("lp_price_linked_item".equals(table)
                    && "ORIGINAL_V1".equals(valueIgnoreCase(row, "expression_version")))
                || ("lp_price_range_factor_rule".equals(table)
                    && valueIgnoreCase(row, "source_upload_batch_id") != null))
            && "PUBLISHED".equals(valueIgnoreCase(row, "rule_status"))
            && pricingMonth != null
            && valueIgnoreCase(row, "pricing_month") != null
            && valueIgnoreCase(row, "pricing_month").toString().compareTo(pricingMonth) <= 0
            && java.util.Objects.equals(
                item.getBusinessUnitType(), valueIgnoreCase(row, "business_unit_type"))) {
          Object org = valueIgnoreCase(row, "org_code");
          if (org != null) originalOrganizations.add(org.toString());
        }
        for (String column : new TreeSet<>(row.keySet())) {
          appendPriceValue(canonical, column, row.get(column));
        }
        append(canonical, "rowEnd", rowIndex);
      }
    }
    if (!originalOrganizations.isEmpty()) {
      append(
          canonical,
          "originalMonths",
          originalMonthRevision(item.getBusinessUnitType(), pricingMonth, originalOrganizations));
      append(
          canonical,
          "financeFactors",
          financePublicationRevision(
              item.getBusinessUnitType(), pricingMonth, originalOrganizations));
    }
    return sha256(canonical.toString());
  }

  /** 与结算选月规则一致：只跟踪该业务单元、核算月可用的最新原稿及实际需要的成组基价。 */
  private String settlementRevision(String businessUnit, String month, List<String> materials) {
    if (month == null || month.isBlank()) return "NO_PRICING_MONTH";
    var parameters = new ArrayList<Object>(java.util.Arrays.asList(businessUnit, month));
    String filter = "business_unit_type=? AND price_month<=? AND active=1";
    if (!materials.isEmpty()) {
      filter += " AND material_code IN (" + String.join(",", Collections.nCopies(materials.size(), "?")) + ")";
      parameters.addAll(materials);
    }
    var rows = jdbcTemplate.queryForList(
        "SELECT ranked.* FROM (SELECT p.*,ROW_NUMBER() OVER (PARTITION BY material_code,price_type "
            + "ORDER BY price_month DESC,revision_no DESC) AS ranking FROM lp_settlement_price p WHERE "
            + filter + ") ranked WHERE ranking=1 ORDER BY material_code,price_type", parameters.toArray());
    StringBuilder canonical = new StringBuilder();
    boolean requiresBase = false;
    for (var row : rows) {
      for (String column : new TreeSet<>(row.keySet())) appendPriceValue(canonical, column, row.get(column));
      Object confirmed = valueIgnoreCase(row, "weights_confirmed");
      requiresBase |= "SETTLE_LINKED".equals(valueIgnoreCase(row, "price_type"))
          && (Boolean.TRUE.equals(confirmed) || "1".equals(String.valueOf(confirmed)))
          && valueIgnoreCase(row, "copper_weight_g") != null
          && valueIgnoreCase(row, "zinc_weight_g") != null;
    }
    if (requiresBase) {
      var bases = jdbcTemplate.queryForList(
          "SELECT * FROM lp_settlement_base_price WHERE business_unit_type=? AND price_month<=? AND active=1 "
              + "ORDER BY price_month DESC,revision_no DESC LIMIT 1", businessUnit, month);
      for (var row : bases)
        for (String column : new TreeSet<>(row.keySet())) appendPriceValue(canonical, column, row.get(column));
    }
    return sha256(canonical.toString());
  }

  /** 新月只导入其他料号，也会改变本料号的选月边界，必须使旧成功失效。 */
  private String originalMonthRevision(
      String businessUnit, String month, java.util.Set<String> organizations) {
    StringBuilder canonical = new StringBuilder();
    for (String org : new TreeSet<>(organizations)) {
      String selected =
          jdbcTemplate.queryForObject(
              """
SELECT MAX(pricing_month) FROM (
  SELECT pricing_month FROM lp_price_linked_item FORCE INDEX (idx_linked_scope_month)
  WHERE business_unit_type=? AND COALESCE(org_code,'')=? AND pricing_month<=?
    AND source_kind='PUBLIC' AND deleted=0 AND rule_status IN ('LEGACY','PUBLISHED')
  UNION ALL
  SELECT pricing_month FROM lp_price_range_factor_rule FORCE INDEX (idx_range_scope_month)
  WHERE business_unit_type=? AND COALESCE(org_code,'')=? AND pricing_month<=?
    AND rule_status IN ('LEGACY','PUBLISHED')
) available_months
""",
              String.class,
              businessUnit,
              org,
              month,
              businessUnit,
              org,
              month);
      append(canonical, org, selected);
    }
    return sha256(canonical.toString());
  }

  /** 原稿依赖不可变财务发布；发布新基价后，正常入口不能复用旧财务输入的成功成本。 */
  String financePublicationRevision(
      String businessUnit, String month, java.util.Set<String> organizations) {
    if (month == null || month.isBlank()) return "NO_PRICING_MONTH";
    String sql =
        "SELECT"
            + " b.id,b.price_org_code,b.price_month,b.publication_revision,b.default_scheme_code,b.content_hash"
            + " FROM lp_factor_upload_batch b WHERE b.business_unit_type=? AND b.price_month<=? AND"
            + " b.import_type='FINANCE_BASE' AND b.status='PUBLISHED' AND b.publication_revision IS"
            + " NOT NULL AND b.published_at IS NOT NULL AND NOT EXISTS (SELECT 1 FROM"
            + " lp_factor_upload_batch n WHERE n.business_unit_type=b.business_unit_type AND"
            + " n.price_org_code=b.price_org_code AND n.price_month<=? AND"
            + " n.import_type='FINANCE_BASE' AND n.status='PUBLISHED' AND n.publication_revision IS"
            + " NOT NULL AND n.published_at IS NOT NULL AND (n.price_month>b.price_month OR"
            + " (n.price_month=b.price_month AND n.publication_revision>b.publication_revision)))";
    List<Object> parameters = new ArrayList<>(java.util.Arrays.asList(businessUnit, month, month));
    if (organizations != null && !organizations.isEmpty()) {
      var ordered = new TreeSet<>(organizations);
      sql +=
          " AND b.price_org_code IN ("
              + String.join(",", Collections.nCopies(ordered.size(), "?"))
              + ")";
      parameters.addAll(ordered);
    }
    sql += " ORDER BY b.price_org_code,b.id";
    StringBuilder canonical = new StringBuilder();
    for (var row : jdbcTemplate.queryForList(sql, parameters.toArray())) {
      for (String column : new TreeSet<>(row.keySet()))
        appendPriceValue(canonical, column, row.get(column));
      // 不能只信发布行ID/摘要：同一因素行的更正、税价单位或身份变更都影响实际计算。
      var factorRows =
          jdbcTemplate.queryForList(
              """
              SELECT p.*, f.canonical_factor_key FROM lp_factor_monthly_price p
              JOIN lp_factor_identity f ON f.id=p.factor_identity_id
              WHERE p.source_upload_batch_id=? AND p.status='PUBLISHED'
                AND p.published_at IS NOT NULL ORDER BY p.id
              """,
              valueIgnoreCase(row, "id"));
      for (var factor : factorRows) {
        for (String column : new TreeSet<>(factor.keySet()))
          appendPriceValue(canonical, column, factor.get(column));
        append(canonical, "factorEnd", valueIgnoreCase(factor, "id"));
      }
    }
    return sha256(canonical.toString());
  }

  private void appendPriceValue(StringBuilder target, String column, Object value) {
    String text = value == null ? "" : value.toString();
    target
        .append(column)
        .append('=')
        .append(value == null ? 'N' : 'V')
        .append(text.length())
        .append(':')
        .append(text)
        .append('\u001f');
  }

  private String effectiveTechnicalFingerprint(OaFormItem item, String pricingMonth) {
    if (effectiveTechnicalDataQueryService == null
        || item == null
        || item.getId() == null
        || pricingMonth == null
        || pricingMonth.isBlank()) {
      return "NO_EFFECTIVE_TECHNICAL_DATA";
    }
    return effectiveTechnicalDataQueryService.effectiveFingerprint(
        item.getId(), pricingMonth.trim());
  }

  private Object valueIgnoreCase(Map<String, Object> row, String key) {
    for (Map.Entry<String, Object> entry : row.entrySet()) {
      if (key.equalsIgnoreCase(entry.getKey())) {
        return entry.getValue();
      }
    }
    return null;
  }

  private void appendForm(StringBuilder value, OaForm form) {
    append(value, "form.id", form.getId());
    append(value, "form.oaNo", form.getOaNo());
    append(value, "form.applyDate", form.getApplyDate());
    append(value, "form.customer", form.getCustomer());
    append(value, "form.quoteScenario", form.getQuoteScenario());
    append(value, "form.expenseProductCategory", form.getExpenseProductCategory());
    append(value, "form.sourceCompany", form.getSourceCompany());
    append(value, "form.sourceBusinessDivision", form.getSourceBusinessDivision());
    append(value, "form.applicantDept", form.getApplicantDept());
    append(value, "form.applicantOffice", form.getApplicantOffice());
    append(value, "form.applicantUnit", form.getApplicantUnit());
    append(value, "form.productAttr", form.getProductAttr());
    append(value, "form.priceLinkMode", form.getPriceLinkMode());
    append(value, "form.overseasSalesMode", form.getOverseasSalesMode());
    append(value, "form.tradeTerms", form.getTradeTerms());
    append(value, "form.exchangeRate", form.getExchangeRate());
    append(value, "form.copperPrice", form.getCopperPrice());
    append(value, "form.zincPrice", form.getZincPrice());
    append(value, "form.aluminumPrice", form.getAluminumPrice());
    append(value, "form.steelPrice", form.getSteelPrice());
    append(value, "form.silverPrice", form.getSilverPrice());
    append(value, "form.goldPrice", form.getGoldPrice());
    append(value, "form.sus304Price", form.getSus304Price());
    append(value, "form.sus316lPrice", form.getSus316lPrice());
    append(value, "form.boardTender", Boolean.TRUE.equals(form.getBoardTender()));
    append(value, "form.otherMaterial", form.getOtherMaterial());
    append(value, "form.baseShipping", form.getBaseShipping());
    append(value, "form.businessUnitType", form.getBusinessUnitType());
    append(value, "form.accountingPeriodMonth", form.getAccountingPeriodMonth());
  }

  private void appendItem(StringBuilder value, OaFormItem item) {
    append(value, "item.id", item.getId());
    append(value, "item.externalLineId", item.getExternalLineId());
    append(value, "item.productName", item.getProductName());
    append(value, "item.customerDrawing", item.getCustomerDrawing());
    append(value, "item.customerCode", item.getCustomerCode());
    append(value, "item.materialNo", item.getMaterialNo());
    append(value, "item.sunlModel", item.getSunlModel());
    append(value, "item.spec", item.getSpec());
    append(value, "item.productAttr", item.getProductAttr());
    append(value, "item.businessType", item.getBusinessType());
    append(value, "item.firstQuoteFlag", item.getFirstQuoteFlag());
    append(value, "item.certificationRequired", item.getCertificationRequired());
    append(value, "item.originCountry", item.getOriginCountry());
    append(value, "item.packageType", item.getPackageType());
    append(value, "item.packageMethod", item.getPackageMethod());
    append(value, "item.packageComponentCode", item.getPackageComponentCode());
    append(value, "item.packageQty", item.getPackageQty());
    append(value, "item.shippingFee", item.getShippingFee());
    append(value, "item.supportQty", item.getSupportQty());
    append(value, "item.annualVolume", item.getAnnualVolume());
    append(value, "item.projectNo", item.getProjectNo());
    append(value, "item.productStatus", item.getProductStatus());
    append(value, "item.scrapRate", item.getScrapRate());
    append(value, "item.unitLaborCost", item.getUnitLaborCost());
    append(value, "item.validMonth", item.getValidMonth());
    append(value, "item.sus304WeightG", item.getSus304WeightG());
    append(value, "item.sus316WeightG", item.getSus316WeightG());
    append(value, "item.copperWeightG", item.getCopperWeightG());
    append(value, "item.validDate", item.getValidDate());
    append(value, "item.businessUnitType", item.getBusinessUnitType());
  }

  private void append(StringBuilder target, String name, Object value) {
    target.append(name).append('=').append(value == null ? "" : value).append('\u001f');
  }

  private String sha256(String value) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("当前 JDK 不支持 SHA-256", exception);
    }
  }
}
