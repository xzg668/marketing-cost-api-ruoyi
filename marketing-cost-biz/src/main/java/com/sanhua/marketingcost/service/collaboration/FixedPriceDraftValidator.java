package com.sanhua.marketingcost.service.collaboration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuotePriceDraft;
import com.sanhua.marketingcost.entity.QuotePriceDraftField;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** QCBP-15：固定采购价和结算固定价复用现有正式表口径的草稿门禁。 */
@Component
public class FixedPriceDraftValidator {
  private static final int AMOUNT_SCALE = 6;

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public FixedPriceDraftValidator(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  public Result validate(
      QuoteCollaborationProductTask task,
      QuotePriceDraft draft,
      List<QuotePriceDraftField> fields) {
    if (!isFixed(draft.getPriceType())) {
      throw new IllegalArgumentException("当前校验器只支持固定采购价和结算固定价");
    }
    List<String> commonErrors = new ArrayList<>();
    Map<String, QuotePriceDraftField> byCode = new LinkedHashMap<>();
    for (QuotePriceDraftField field : fields) {
      field.setValidationStatus("PASSED");
      field.setValidationMessage(null);
      byCode.put(field.getFieldCode(), field);
    }

    if (!StringUtils.hasText(draft.getSupplierCode())
        && !StringUtils.hasText(draft.getSupplierName())) {
      commonErrors.add("供应商编码或供应商名称至少填写一项");
    }
    if (!StringUtils.hasText(draft.getUnit())) commonErrors.add("单位不能为空");
    if (draft.getTaxIncluded() == null
        || (draft.getTaxIncluded() != 0 && draft.getTaxIncluded() != 1)) {
      commonErrors.add("请选择含税或不含税");
    }
    if (draft.getTaxRate() == null) {
      commonErrors.add("税率不能为空");
    } else if (draft.getTaxRate().compareTo(BigDecimal.ZERO) < 0
        || draft.getTaxRate().compareTo(BigDecimal.ONE) > 0
        || draft.getTaxRate().scale() > AMOUNT_SCALE) {
      commonErrors.add("税率必须为0到1之间且最多6位小数");
    }
    if (draft.getEffectiveFrom() == null) commonErrors.add("生效日期不能为空");
    if (draft.getEffectiveFrom() != null && draft.getEffectiveTo() != null
        && draft.getEffectiveTo().isBefore(draft.getEffectiveFrom())) {
      commonErrors.add("失效日期不能早于生效日期");
    }
    validateAccountingMonth(task, draft, commonErrors);

    String amountCode = "SETTLE_FIXED".equals(draft.getPriceType())
        ? "BASE_SETTLE_PRICE" : "PRICE";
    QuotePriceDraftField amountField = byCode.get(amountCode);
    BigDecimal amount = decimal(amountField);
    if (amountField == null) {
      commonErrors.add("价格字段不存在，请重新建立草稿");
    } else if (amount == null) {
      fail(amountField, "价格不能为空");
    } else if (amount.compareTo(BigDecimal.ZERO) <= 0) {
      fail(amountField, "价格必须大于0");
    } else if (amount.scale() > AMOUNT_SCALE || amount.precision() > 18) {
      fail(amountField, "价格最多18位数字、6位小数");
    }

    QuotePriceDraftField markup = byCode.get("MARKUP_RATIO");
    BigDecimal markupValue = decimal(markup);
    if (markupValue != null && (markupValue.compareTo(BigDecimal.ZERO) < 0
        || markupValue.compareTo(new BigDecimal("10")) > 0
        || markupValue.scale() > AMOUNT_SCALE)) {
      fail(markup, "加价比例必须为0到10之间且最多6位小数");
    }

    if (commonErrors.isEmpty() && amount != null && amount.compareTo(BigDecimal.ZERO) > 0
        && conflictsWithFormalPrice(task, draft)) {
      commonErrors.add("目标物料、供应商和生效期与现有正式价格冲突");
    }

    List<String> fieldErrors = fields.stream()
        .filter(field -> "FAILED".equals(field.getValidationStatus()))
        .map(field -> field.getFieldName() + "：" + field.getValidationMessage())
        .toList();
    List<String> errors = new ArrayList<>(commonErrors);
    errors.addAll(fieldErrors);
    return new Result(errors.isEmpty(), errors.isEmpty() ? "校验通过，等待统一提交"
        : String.join("；", errors), fields, taxConversion(amount, draft));
  }

  private void validateAccountingMonth(
      QuoteCollaborationProductTask task, QuotePriceDraft draft, List<String> errors) {
    if (draft.getEffectiveFrom() == null) return;
    YearMonth month = YearMonth.parse(task.getAccountingMonth());
    LocalDate start = month.atDay(1);
    LocalDate end = month.atEndOfMonth();
    if (draft.getEffectiveFrom().isAfter(end)
        || (draft.getEffectiveTo() != null && draft.getEffectiveTo().isBefore(start))) {
      errors.add("生效期未覆盖当前核算月份" + task.getAccountingMonth());
    }
  }

  private boolean conflictsWithFormalPrice(
      QuoteCollaborationProductTask task, QuotePriceDraft draft) {
    String[] sourceTypes = "SETTLE_FIXED".equals(draft.getPriceType())
        ? new String[] {"SETTLE_FIXED", "SETTLE"}
        : new String[] {"PURCHASE_FIXED", "PURCHASE"};
    String supplierCode = trim(draft.getSupplierCode());
    String supplierName = trim(draft.getSupplierName());
    Integer count = jdbc.queryForObject("""
        SELECT COUNT(*) FROM lp_price_fixed_item
        WHERE material_code = ? AND business_unit_type = ?
          AND source_type IN (?, ?)
          AND (org_code = ? OR org_code IS NULL OR org_code = '')
          AND ((? IS NOT NULL AND supplier_code = ?)
               OR (? IS NULL AND ? IS NOT NULL AND supplier_name = ?)
               OR (? IS NULL AND ? IS NULL AND (supplier_code IS NULL OR supplier_code = '')))
          AND (effective_to IS NULL OR effective_to >= ?)
          AND (? IS NULL OR effective_from IS NULL OR effective_from <= ?)
        """, Integer.class, draft.getMaterialCode(), task.getBusinessUnitType(),
        sourceTypes[0], sourceTypes[1], task.getApplicableOrgCode(),
        supplierCode, supplierCode, supplierCode, supplierName, supplierName,
        supplierCode, supplierName, draft.getEffectiveFrom(),
        draft.getEffectiveTo(), draft.getEffectiveTo());
    return count != null && count > 0;
  }

  private TaxConversion taxConversion(BigDecimal amount, QuotePriceDraft draft) {
    if (amount == null || draft.getTaxIncluded() == null || draft.getTaxRate() == null
        || draft.getTaxRate().compareTo(BigDecimal.ZERO) < 0) return null;
    BigDecimal factor = BigDecimal.ONE.add(draft.getTaxRate());
    BigDecimal included = draft.getTaxIncluded() == 1
        ? amount : amount.multiply(factor).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    BigDecimal excluded = draft.getTaxIncluded() == 0
        ? amount : amount.divide(factor, AMOUNT_SCALE, RoundingMode.HALF_UP);
    return new TaxConversion(included.toPlainString(), excluded.toPlainString());
  }

  private BigDecimal decimal(QuotePriceDraftField field) {
    if (field == null || !StringUtils.hasText(field.getTargetValueJson())) return null;
    try {
      JsonNode node = objectMapper.readTree(field.getTargetValueJson());
      String text = node.isTextual() ? node.asText() : node.toString();
      return StringUtils.hasText(text) ? new BigDecimal(text.trim()) : null;
    } catch (JsonProcessingException | NumberFormatException exception) {
      fail(field, "请输入合法数字");
      return null;
    }
  }

  private static void fail(QuotePriceDraftField field, String message) {
    if (field == null) return;
    field.setValidationStatus("FAILED");
    field.setValidationMessage(message);
  }

  private static boolean isFixed(String priceType) {
    return "FIXED_PURCHASE".equals(priceType) || "SETTLE_FIXED".equals(priceType);
  }

  private static String trim(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  public record Result(
      boolean valid, String message, List<QuotePriceDraftField> fields,
      TaxConversion taxConversion) {}

  public record TaxConversion(String taxIncludedPrice, String taxExcludedPrice) {}
}
