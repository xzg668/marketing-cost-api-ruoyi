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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** QCBP-17：完整区间组、统一边界和当前报价值唯一命中的草稿门禁。 */
@Component
public class RangePriceDraftValidator {
  private static final int SCALE = 6;
  private static final int PRECISION = 18;

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public RangePriceDraftValidator(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  public Result validate(
      QuoteCollaborationProductTask task,
      QuotePriceDraft draft,
      List<QuotePriceDraftField> fields) {
    if (!"RANGE".equals(draft.getPriceType())) {
      throw new IllegalArgumentException("当前校验器只支持区间价草稿");
    }
    fields.forEach(this::reset);
    List<String> commonErrors = validateCommon(task, draft);
    QuotePriceDraftField basisField = find(fields, "COMMON", "RANGE_BASIS");
    String basis = text(basisField);
    if (!StringUtils.hasText(basis)) basis = "QTY";
    basis = basis.toUpperCase(Locale.ROOT);
    if (!List.of("QTY", "FACTOR").contains(basis)) {
      fail(basisField, "区间依据只能选择报价数量或报价行情");
    }
    QuotePriceDraftField factorField = find(fields, "COMMON", "FACTOR_CODE");
    String factorCode = text(factorField);
    if ("FACTOR".equals(basis) && !supportedFactor(factorCode)) {
      fail(factorField, "报价行情必须选择铜、锌、铝、黄金、白银、SUS304或SUS316L");
    }

    Map<String, Row> grouped = new LinkedHashMap<>();
    for (QuotePriceDraftField field : fields) {
      if (!"RANGE_ROW".equals(field.getSectionCode())) continue;
      Row row = grouped.computeIfAbsent(field.getRowKey(), Row::new);
      row.put(field);
    }
    if (grouped.isEmpty()) commonErrors.add("区间价至少保留一段区间");
    List<Row> rows = new ArrayList<>(grouped.values());
    for (Row row : rows) validateRow(row, draft);
    if (rows.stream().allMatch(row -> row.low != null)) {
      rows.sort(Comparator.comparing(row -> row.low));
      validateBoundaries(rows);
    }

    BigDecimal current = null;
    if (commonErrors.isEmpty() && !hasFailure(fields)) {
      current = "FACTOR".equals(basis)
          ? currentFactor(task.getId(), factorCode)
          : currentQuantity(task.getId());
      if (current == null) {
        commonErrors.add("FACTOR".equals(basis)
            ? "当前报价单没有可用的" + factorLabel(factorCode) + "，无法校验区间命中"
            : "当前报价产品没有预计年用量或报价数量，无法校验区间命中");
      } else {
        int hit = hitCount(rows, current);
        if (hit != 1) {
          commonErrors.add("当前" + ("FACTOR".equals(basis) ? factorLabel(factorCode) : "报价数量")
              + current.stripTrailingZeros().toPlainString() + "命中" + hit + "段，必须且只能命中一段");
        }
      }
    }
    List<String> errors = new ArrayList<>(commonErrors);
    fields.stream().filter(field -> "FAILED".equals(field.getValidationStatus()))
        .map(field -> field.getFieldName() + "：" + field.getValidationMessage())
        .forEach(errors::add);
    String message = errors.isEmpty()
        ? "区间组校验通过，当前" + ("FACTOR".equals(basis) ? factorLabel(factorCode) : "报价数量")
            + current.stripTrailingZeros().toPlainString() + "唯一命中一段，等待统一提交"
        : String.join("；", errors);
    return new Result(errors.isEmpty(), message, fields);
  }

  private List<String> validateCommon(
      QuoteCollaborationProductTask task, QuotePriceDraft draft) {
    List<String> errors = new ArrayList<>();
    if (!StringUtils.hasText(draft.getSupplierCode())
        && !StringUtils.hasText(draft.getSupplierName())) {
      errors.add("供应商编码或供应商名称至少填写一项");
    }
    if (!StringUtils.hasText(draft.getUnit())) errors.add("单位不能为空");
    if (draft.getTaxIncluded() == null
        || (draft.getTaxIncluded() != 0 && draft.getTaxIncluded() != 1)) {
      errors.add("请选择含税或不含税");
    }
    if (draft.getTaxRate() == null) {
      errors.add("税率不能为空");
    } else if (!validDecimal(draft.getTaxRate())
        || draft.getTaxRate().compareTo(BigDecimal.ZERO) < 0
        || draft.getTaxRate().compareTo(BigDecimal.ONE) > 0) {
      errors.add("税率必须为0到1之间且最多6位小数");
    }
    if (draft.getEffectiveFrom() == null) {
      errors.add("生效日期不能为空");
    } else {
      YearMonth month = YearMonth.parse(task.getAccountingMonth());
      LocalDate start = month.atDay(1);
      LocalDate end = month.atEndOfMonth();
      if (draft.getEffectiveFrom().isAfter(end)
          || (draft.getEffectiveTo() != null && draft.getEffectiveTo().isBefore(start))) {
        errors.add("生效期未覆盖当前核算月份" + task.getAccountingMonth());
      }
    }
    if (draft.getEffectiveFrom() != null && draft.getEffectiveTo() != null
        && draft.getEffectiveTo().isBefore(draft.getEffectiveFrom())) {
      errors.add("失效日期不能早于生效日期");
    }
    return errors;
  }

  private void validateRow(Row row, QuotePriceDraft draft) {
    row.low = decimal(row.field("RANGE_LOW"));
    row.high = decimal(row.field("RANGE_HIGH"));
    row.excl = decimal(row.field("PRICE_EXCL_TAX"));
    row.incl = decimal(row.field("PRICE_INCL_TAX"));
    if (row.low == null && !failed(row.field("RANGE_LOW"))) {
      fail(row.field("RANGE_LOW"), "区间下限不能为空");
    } else if (row.low != null && row.low.compareTo(BigDecimal.ZERO) < 0) {
      fail(row.field("RANGE_LOW"), "区间下限不能小于0");
    }
    validateNumber(row.field("RANGE_LOW"), row.low, "区间下限");
    validateNumber(row.field("RANGE_HIGH"), row.high, "区间上限");
    if (row.low != null && row.high != null && row.high.compareTo(row.low) <= 0) {
      fail(row.field("RANGE_HIGH"), "区间上限必须大于下限");
    }
    validatePositivePrice(row.field("PRICE_EXCL_TAX"), row.excl);
    validatePositivePrice(row.field("PRICE_INCL_TAX"), row.incl);
    if (row.excl == null && row.incl == null
        && !failed(row.field("PRICE_EXCL_TAX")) && !failed(row.field("PRICE_INCL_TAX"))) {
      fail(row.field("PRICE_INCL_TAX"), "含税价和不含税价至少填写一项");
    }
    if (draft.getTaxRate() != null && draft.getTaxRate().compareTo(BigDecimal.ZERO) >= 0
        && draft.getTaxRate().compareTo(BigDecimal.ONE) <= 0) {
      BigDecimal factor = BigDecimal.ONE.add(draft.getTaxRate());
      if (row.excl == null && row.incl != null && row.incl.compareTo(BigDecimal.ZERO) > 0) {
        row.excl = row.incl.divide(factor, SCALE, RoundingMode.HALF_UP);
        set(row.field("PRICE_EXCL_TAX"), row.excl);
      } else if (row.incl == null && row.excl != null && row.excl.compareTo(BigDecimal.ZERO) > 0) {
        row.incl = row.excl.multiply(factor).setScale(SCALE, RoundingMode.HALF_UP);
        set(row.field("PRICE_INCL_TAX"), row.incl);
      }
    }
  }

  private void validateBoundaries(List<Row> rows) {
    for (int index = 0; index < rows.size(); index++) {
      Row current = rows.get(index);
      if (index < rows.size() - 1 && current.high == null) {
        fail(current.field("RANGE_HIGH"), "只有最后一段的上限可以为空");
        continue;
      }
      if (index == 0) continue;
      Row previous = rows.get(index - 1);
      if (previous.low != null && current.low.compareTo(previous.low) == 0) {
        fail(current.field("RANGE_LOW"), "区间下限重复");
        continue;
      }
      if (previous.high == null) continue;
      int compare = current.low.compareTo(previous.high);
      if (compare < 0) {
        fail(current.field("RANGE_LOW"), "与上一段重叠；按下限含、上限不含处理");
      } else if (compare > 0) {
        fail(current.field("RANGE_LOW"), "与上一段之间存在空档；本段下限应等于上一段上限");
      }
    }
  }

  private int hitCount(List<Row> rows, BigDecimal value) {
    int hit = 0;
    for (int index = 0; index < rows.size(); index++) {
      Row row = rows.get(index);
      if (row.low == null || value.compareTo(row.low) < 0) continue;
      boolean last = index == rows.size() - 1;
      if (row.high == null || value.compareTo(row.high) < 0
          || (last && value.compareTo(row.high) == 0)) hit++;
    }
    return hit;
  }

  private BigDecimal currentQuantity(Long taskId) {
    List<BigDecimal> rows = jdbc.query("""
        SELECT COALESCE(i.annual_volume, i.support_qty) AS current_value
        FROM lp_quote_collaboration_quote_link l
        JOIN oa_form_item i ON i.id = l.oa_form_item_id AND i.deleted = 0
        WHERE l.product_task_id = ? AND l.active_flag = 1
        ORDER BY CASE WHEN l.link_type = 'OWNER' THEN 0 ELSE 1 END, l.id LIMIT 1
        """, (rs, rowNum) -> rs.getBigDecimal("current_value"), taskId);
    return rows.isEmpty() ? null : rows.get(0);
  }

  private BigDecimal currentFactor(Long taskId, String factorCode) {
    String column = switch (factorCode == null ? "" : factorCode.trim().toUpperCase(Locale.ROOT)) {
      case "CU" -> "copper_price";
      case "ZN" -> "zinc_price";
      case "AL" -> "aluminum_price";
      case "GOLD" -> "gold_price";
      case "SILVER" -> "silver_price";
      case "SUS304" -> "sus304_price";
      case "SUS316", "SUS316L" -> "sus316l_price";
      default -> null;
    };
    if (column == null) return null;
    List<BigDecimal> rows = jdbc.query("""
        SELECT f.%s AS current_value
        FROM lp_quote_collaboration_quote_link l
        JOIN oa_form f ON f.id = l.oa_form_id AND f.deleted = 0
        WHERE l.product_task_id = ? AND l.active_flag = 1
        ORDER BY CASE WHEN l.link_type = 'OWNER' THEN 0 ELSE 1 END, l.id LIMIT 1
        """.formatted(column), (rs, rowNum) -> rs.getBigDecimal("current_value"), taskId);
    return rows.isEmpty() ? null : rows.get(0);
  }

  private BigDecimal decimal(QuotePriceDraftField field) {
    if (field == null || !StringUtils.hasText(field.getTargetValueJson())) return null;
    try {
      JsonNode node = objectMapper.readTree(field.getTargetValueJson());
      String value = node.isTextual() ? node.asText() : node.toString();
      return StringUtils.hasText(value) ? new BigDecimal(value.trim()) : null;
    } catch (JsonProcessingException | NumberFormatException exception) {
      fail(field, "请输入合法数字");
      return null;
    }
  }

  private void validateNumber(QuotePriceDraftField field, BigDecimal value, String label) {
    if (value != null && !validDecimal(value)) fail(field, label + "最多18位数字、6位小数");
  }

  private void validatePositivePrice(QuotePriceDraftField field, BigDecimal value) {
    if (value == null) return;
    if (!validDecimal(value)) fail(field, "价格最多18位数字、6位小数");
    else if (value.compareTo(BigDecimal.ZERO) <= 0) fail(field, "价格必须大于0");
  }

  private static boolean validDecimal(BigDecimal value) {
    return value.precision() <= PRECISION && Math.max(value.scale(), 0) <= SCALE;
  }

  private String text(QuotePriceDraftField field) {
    if (field == null || !StringUtils.hasText(field.getTargetValueJson())) return null;
    try {
      JsonNode node = objectMapper.readTree(field.getTargetValueJson());
      return node.isNull() ? null : node.asText().trim();
    } catch (JsonProcessingException exception) {
      return field.getTargetValueJson().trim();
    }
  }

  private void set(QuotePriceDraftField field, BigDecimal value) {
    if (field == null) return;
    try {
      field.setTargetValueJson(objectMapper.writeValueAsString(
          value.stripTrailingZeros().toPlainString()));
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("区间价格无法序列化", exception);
    }
  }

  private static QuotePriceDraftField find(
      List<QuotePriceDraftField> fields, String section, String code) {
    return fields.stream().filter(field -> section.equals(field.getSectionCode())
        && code.equals(field.getFieldCode())).findFirst().orElse(null);
  }

  private void reset(QuotePriceDraftField field) {
    field.setValidationStatus("PASSED");
    field.setValidationMessage(null);
  }

  private static void fail(QuotePriceDraftField field, String message) {
    if (field == null) return;
    field.setValidationStatus("FAILED");
    field.setValidationMessage(message);
  }

  private static boolean failed(QuotePriceDraftField field) {
    return field != null && "FAILED".equals(field.getValidationStatus());
  }

  private static boolean hasFailure(List<QuotePriceDraftField> fields) {
    return fields.stream().anyMatch(RangePriceDraftValidator::failed);
  }

  private static boolean supportedFactor(String factor) {
    if (!StringUtils.hasText(factor)) return false;
    return List.of("CU", "ZN", "AL", "GOLD", "SILVER", "SUS304", "SUS316", "SUS316L")
        .contains(factor.trim().toUpperCase(Locale.ROOT));
  }

  private static String factorLabel(String factor) {
    return switch (factor == null ? "" : factor.trim().toUpperCase(Locale.ROOT)) {
      case "CU" -> "铜价";
      case "ZN" -> "锌价";
      case "AL" -> "铝价";
      case "GOLD" -> "黄金价";
      case "SILVER" -> "白银价";
      case "SUS304" -> "SUS304价";
      case "SUS316", "SUS316L" -> "SUS316L价";
      default -> "报价行情";
    };
  }

  public record Result(boolean valid, String message, List<QuotePriceDraftField> fields) {}

  private static final class Row {
    private final String key;
    private final Map<String, QuotePriceDraftField> fields = new LinkedHashMap<>();
    private BigDecimal low;
    private BigDecimal high;
    private BigDecimal excl;
    private BigDecimal incl;

    private Row(String key) { this.key = key; }

    private void put(QuotePriceDraftField field) {
      if (fields.putIfAbsent(field.getFieldCode(), field) != null) {
        throw new IllegalArgumentException("区间行字段重复：" + key + "/" + field.getFieldCode());
      }
    }

    private QuotePriceDraftField field(String code) { return fields.get(code); }
  }
}
