package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.service.CostInputRevisionService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class CostInputRevisionServiceImpl implements CostInputRevisionService {

  private static final List<String> SOURCE_TABLES = List.of(
      "cms_cost_source_effective",
      "lp_department_fund_rate",
      "lp_quality_loss_rate",
      "lp_manufacture_rate",
      "lp_three_expense_rate",
      "lp_other_expense_rate",
      "lp_product_property",
      "lp_price_fixed_item",
      "lp_price_linked_item",
      "lp_price_range_item",
      "lp_price_range_factor_rule",
      "lp_price_settle",
      "lp_price_settle_item",
      "lp_supplier_supply_ratio",
      "lp_material_price_type",
      "lp_finance_base_price",
      "lp_quote_base_price_mapping_rule",
      "lp_bom_byproduct_cost_rule",
      "lp_cost_business_rule");

  private final JdbcTemplate jdbcTemplate;

  public CostInputRevisionServiceImpl(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public String currentRevision(OaForm form, OaFormItem item) {
    if (form == null || item == null) {
      throw new IllegalArgumentException("报价表头和产品行不能为空");
    }
    return scopedRevision(sourceRevision(), form, item);
  }

  @Override
  public Map<Long, String> currentRevisions(OaForm form, List<OaFormItem> items) {
    String sourceRevision = sourceRevision();
    Map<Long, String> revisions = new LinkedHashMap<>();
    if (items != null) {
      for (OaFormItem item : items) {
        if (item != null && item.getId() != null) {
          revisions.put(item.getId(), scopedRevision(sourceRevision, form, item));
        }
      }
    }
    return revisions;
  }

  private String sourceRevision() {
    StringBuilder canonical = new StringBuilder(1024);
    for (String table : SOURCE_TABLES) {
      List<Map<String, Object>> rows = jdbcTemplate.queryForList("CHECKSUM TABLE `" + table + "`");
      Object checksum = rows.isEmpty() ? null : valueIgnoreCase(rows.get(0), "Checksum");
      append(canonical, table, checksum);
    }
    return sha256(canonical.toString());
  }

  private String scopedRevision(String sourceRevision, OaForm form, OaFormItem item) {
    StringBuilder canonical = new StringBuilder(1024);
    append(canonical, "sources", sourceRevision);
    appendForm(canonical, form);
    appendItem(canonical, item);
    return sha256(canonical.toString());
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
      return java.util.HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("当前 JDK 不支持 SHA-256", exception);
    }
  }
}
