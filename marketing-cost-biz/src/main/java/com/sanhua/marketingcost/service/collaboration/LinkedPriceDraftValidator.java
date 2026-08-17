package com.sanhua.marketingcost.service.collaboration;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.PriceLinkedFormulaPreviewResponse;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuotePriceDraft;
import com.sanhua.marketingcost.entity.QuotePriceDraftField;
import com.sanhua.marketingcost.formula.normalize.FormulaDisplayRenderer;
import com.sanhua.marketingcost.formula.normalize.FormulaNormalizer;
import com.sanhua.marketingcost.formula.normalize.FormulaSyntaxException;
import com.sanhua.marketingcost.formula.normalize.FormulaValidator;
import com.sanhua.marketingcost.formula.registry.ExpressionEvaluator;
import com.sanhua.marketingcost.formula.registry.RowLocalPlaceholderRegistry;
import com.sanhua.marketingcost.mapper.PriceVariableMapper;
import com.sanhua.marketingcost.service.PriceLinkedFormulaPreviewService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** QCBP-16：联动公式、目标技术变量和当前取价的草稿门禁。 */
@Component
public class LinkedPriceDraftValidator {
  private static final int DECIMAL_SCALE = 6;
  private static final int DECIMAL_PRECISION = 18;

  private final FormulaNormalizer formulaNormalizer;
  private final FormulaValidator formulaValidator;
  private final FormulaDisplayRenderer displayRenderer;
  private final PriceLinkedFormulaPreviewService previewService;
  private final PriceVariableMapper variableMapper;
  private final RowLocalPlaceholderRegistry placeholderRegistry;
  private final ObjectMapper objectMapper;

  public LinkedPriceDraftValidator(
      FormulaNormalizer formulaNormalizer,
      FormulaValidator formulaValidator,
      FormulaDisplayRenderer displayRenderer,
      PriceLinkedFormulaPreviewService previewService,
      PriceVariableMapper variableMapper,
      RowLocalPlaceholderRegistry placeholderRegistry,
      ObjectMapper objectMapper) {
    this.formulaNormalizer = formulaNormalizer;
    this.formulaValidator = formulaValidator;
    this.displayRenderer = displayRenderer;
    this.previewService = previewService;
    this.variableMapper = variableMapper;
    this.placeholderRegistry = placeholderRegistry;
    this.objectMapper = objectMapper;
  }

  public Result validate(
      QuoteCollaborationProductTask task,
      QuotePriceDraft draft,
      List<QuotePriceDraftField> sourceFields) {
    if (!"LINKED".equals(draft.getPriceType())) {
      throw new IllegalArgumentException("当前校验器只支持联动价草稿");
    }
    List<QuotePriceDraftField> fields = new ArrayList<>(sourceFields);
    fields.forEach(this::resetValidation);
    QuotePriceDraftField formulaField = field(fields, "FORMULA", "FORMULA_EXPR");
    if (formulaField == null) {
      formulaField = newField(draft.getId(), "FORMULA", "MAIN", "FORMULA_EXPR",
          "联动公式", "TEXT", null, true, true, null, 10);
      fields.add(formulaField);
    }

    String normalized;
    try {
      String raw = jsonText(formulaField.getTargetValueJson());
      if (!StringUtils.hasText(raw)) {
        fail(formulaField, "联动公式不能为空");
        return result(fields);
      }
      normalized = formulaNormalizer.normalize(raw.trim());
      if (normalized.length() > 512) {
        fail(formulaField, "联动公式不能超过512个字符");
        return result(fields);
      }
      formulaValidator.validate(normalized);
      formulaField.setTargetValueJson(jsonValue(normalized));
    } catch (FormulaSyntaxException | IllegalArgumentException exception) {
      fail(formulaField, readableFormulaError(exception));
      return result(fields);
    }

    LinkedHashSet<String> tokens = ExpressionEvaluator.extractVariables(normalized);
    List<PriceVariable> variables = registeredVariables(tokens);
    List<TechnicalVariable> technicalVariables = technicalVariables(tokens, variables);
    fields = synchronizeTechnicalFields(fields, draft.getId(), technicalVariables);
    formulaField = field(fields, "FORMULA", "FORMULA_EXPR");
    upsertFormulaDisplay(fields, draft.getId(), normalized);

    validateCommon(task, draft, formulaField);
    PriceLinkedItem targetItem = targetItem(task, draft, normalized);
    BeanWrapperImpl itemWrapper = new BeanWrapperImpl(targetItem);
    for (TechnicalVariable variable : technicalVariables) {
      QuotePriceDraftField input = variableField(fields, variable);
      BigDecimal value = decimal(input);
      if (input == null) {
        continue;
      }
      if (value == null) {
        if (!"FAILED".equals(input.getValidationStatus())) fail(input, "请输入本料号自己的值");
        continue;
      }
      if (value.precision() > DECIMAL_PRECISION || value.scale() > DECIMAL_SCALE) {
        fail(input, "最多18位数字、6位小数");
        continue;
      }
      if (variable.weight() && value.compareTo(BigDecimal.ZERO) <= 0) {
        fail(input, "重量必须大于0");
        continue;
      }
      if (!variable.weight() && value.compareTo(BigDecimal.ZERO) < 0) {
        fail(input, "金额不能小于0");
        continue;
      }
      if (!itemWrapper.isWritableProperty(variable.propertyName())) {
        fail(input, "变量注册字段无法写入联动价上下文，请联系管理员");
        continue;
      }
      itemWrapper.setPropertyValue(variable.propertyName(), value);
    }
    validateWeightRelation(fields, technicalVariables);

    if (hasFailures(fields)) return result(fields);
    String evaluableFormula = bindRowLocalPlaceholders(normalized, fields, formulaField);
    if ("FAILED".equals(formulaField.getValidationStatus())) return result(fields);
    try {
      formulaValidator.validate(evaluableFormula);
      targetItem.setFormulaExpr(evaluableFormula);
      PriceLinkedFormulaPreviewResponse preview = previewService.previewForRefresh(
          targetItem, Map.of());
      validatePreview(preview, formulaField);
    } catch (RuntimeException exception) {
      fail(formulaField, "公式当前取价校验失败：" + safeMessage(exception));
    }
    return result(fields);
  }

  private void validateCommon(
      QuoteCollaborationProductTask task, QuotePriceDraft draft, QuotePriceDraftField formula) {
    List<String> errors = new ArrayList<>();
    if (!StringUtils.hasText(draft.getUnit())) errors.add("单位不能为空");
    if (draft.getTaxIncluded() == null
        || (draft.getTaxIncluded() != 0 && draft.getTaxIncluded() != 1)) {
      errors.add("请选择含税或不含税");
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
    if (!errors.isEmpty()) fail(formula, String.join("；", errors));
  }

  private List<PriceVariable> registeredVariables(Set<String> tokens) {
    if (tokens.isEmpty()) return List.of();
    List<PriceVariable> rows = variableMapper.selectList(
        Wrappers.lambdaQuery(PriceVariable.class)
            .eq(PriceVariable::getStatus, "active")
            .in(PriceVariable::getVariableCode, tokens));
    return rows == null ? List.of() : rows;
  }

  private List<TechnicalVariable> technicalVariables(
      LinkedHashSet<String> tokens, List<PriceVariable> registered) {
    Map<String, PriceVariable> byCode = new HashMap<>();
    for (PriceVariable variable : registered) {
      if (StringUtils.hasText(variable.getVariableCode())) {
        byCode.put(variable.getVariableCode(), variable);
      }
    }
    Map<String, List<PriceVariable>> bySourceField = new LinkedHashMap<>();
    for (String token : tokens) {
      PriceVariable variable = byCode.get(token);
      if (variable == null || !"PART_CONTEXT".equalsIgnoreCase(variable.getFactorType())
          || !"LINKED_ITEM".equalsIgnoreCase(variable.getSourceType())
          || !StringUtils.hasText(variable.getSourceField())) continue;
      String sourceField = variable.getSourceField().trim();
      bySourceField.computeIfAbsent(sourceField, ignored -> new ArrayList<>()).add(variable);
    }
    List<TechnicalVariable> result = new ArrayList<>();
    int sort = 30;
    for (Map.Entry<String, List<PriceVariable>> entry : bySourceField.entrySet()) {
      String sourceField = entry.getKey();
      List<PriceVariable> aliases = entry.getValue();
      PriceVariable canonical = aliases.stream()
          .filter(row -> sourceField.equalsIgnoreCase(row.getVariableCode()))
          .findFirst().orElse(aliases.get(0));
      result.add(new TechnicalVariable(
          canonical.getVariableCode(),
          StringUtils.hasText(canonical.getVariableName())
              ? canonical.getVariableName() : canonical.getVariableCode(),
          sourceField, toCamel(sourceField), isWeight(sourceField),
          aliases.stream().map(PriceVariable::getVariableCode).filter(Objects::nonNull).toList(),
          sort));
      sort += 10;
    }
    return result;
  }

  private List<QuotePriceDraftField> synchronizeTechnicalFields(
      List<QuotePriceDraftField> fields, Long draftId, List<TechnicalVariable> variables) {
    List<QuotePriceDraftField> result = new ArrayList<>();
    List<QuotePriceDraftField> oldVariables = fields.stream()
        .filter(row -> "VARIABLE".equals(row.getSectionCode())).toList();
    fields.stream().filter(row -> !"VARIABLE".equals(row.getSectionCode())).forEach(result::add);
    for (TechnicalVariable variable : variables) {
      QuotePriceDraftField existing = oldVariables.stream()
          .filter(row -> matchesVariable(row, variable)).findFirst().orElse(null);
      QuotePriceDraftField next = existing == null
          ? newField(draftId, "VARIABLE", "MAIN", variable.code(), variable.name(), "DECIMAL",
              null, true, true, variable.unit(), variable.sort())
          : existing;
      next.setFieldCode(variable.code());
      next.setFieldName(variable.name());
      next.setUnit(variable.unit());
      next.setRequiredFlag(1);
      next.setTechInputRequired(1);
      next.setSortSeq(variable.sort());
      resetValidation(next);
      result.add(next);
    }
    result.sort(Comparator.comparingInt(row -> row.getSortSeq() == null ? 0 : row.getSortSeq()));
    return result;
  }

  private void upsertFormulaDisplay(
      List<QuotePriceDraftField> fields, Long draftId, String normalized) {
    QuotePriceDraftField display = field(fields, "FORMULA", "FORMULA_EXPR_CN");
    if (display == null) {
      display = newField(draftId, "FORMULA", "MAIN", "FORMULA_EXPR_CN", "公式说明",
          "TEXT", null, false, false, null, 20);
      fields.add(display);
    }
    display.setTargetValueJson(jsonValue(displayRenderer.renderCn(normalized)));
    resetValidation(display);
  }

  private PriceLinkedItem targetItem(
      QuoteCollaborationProductTask task, QuotePriceDraft draft, String formula) {
    PriceLinkedItem item = new PriceLinkedItem();
    item.setMaterialCode(draft.getMaterialCode());
    item.setMaterialName(draft.getMaterialName());
    item.setSpecModel(firstText(draft.getMaterialModel(), draft.getMaterialSpec()));
    item.setBusinessUnitType(task.getBusinessUnitType());
    item.setOrgCode(task.getApplicableOrgCode());
    item.setPricingMonth(task.getAccountingMonth());
    item.setSupplierCode(draft.getSupplierCode());
    item.setSupplierName(draft.getSupplierName());
    item.setUnit(draft.getUnit());
    item.setTaxIncluded(draft.getTaxIncluded());
    item.setEffectiveFrom(draft.getEffectiveFrom());
    item.setEffectiveTo(draft.getEffectiveTo());
    item.setFormulaExpr(formula);
    return item;
  }

  private String bindRowLocalPlaceholders(
      String formula, List<QuotePriceDraftField> fields, QuotePriceDraftField formulaField) {
    Map<String, Map<String, String>> bindingRows = new LinkedHashMap<>();
    for (QuotePriceDraftField field : fields) {
      if (!"BINDING".equals(field.getSectionCode())) continue;
      bindingRows.computeIfAbsent(field.getRowKey(), ignored -> new HashMap<>())
          .put(field.getFieldCode(), jsonText(field.getTargetValueJson()));
    }
    Map<String, String> tokenNameToPlaceholder = new HashMap<>();
    placeholderRegistry.tokenNames().forEach((code, names) -> names.forEach(name ->
        tokenNameToPlaceholder.put(name, code)));
    Map<String, String> factors = new HashMap<>();
    for (Map<String, String> row : bindingRows.values()) {
      String placeholder = tokenNameToPlaceholder.get(row.get("TOKEN_NAME"));
      String factor = row.get("FACTOR_CODE");
      if (StringUtils.hasText(placeholder) && StringUtils.hasText(factor)) {
        factors.put(placeholder, factor);
      }
    }
    String result = formula;
    for (String token : ExpressionEvaluator.extractVariables(formula)) {
      if (!placeholderRegistry.isKnown(token)) continue;
      String factor = factors.get(token);
      if (!StringUtils.hasText(factor)) {
        fail(formulaField, "公式中的系统行情占位符 [" + token + "] 没有随参考记录复制绑定");
        return formula;
      }
      result = result.replace("[" + token + "]", "[" + factor + "]");
    }
    return result;
  }

  private void validatePreview(
      PriceLinkedFormulaPreviewResponse preview, QuotePriceDraftField formulaField) {
    if (preview == null) {
      fail(formulaField, "公式当前取价校验没有返回结果");
      return;
    }
    if (StringUtils.hasText(preview.getError())) {
      fail(formulaField, "公式当前取价校验失败：" + preview.getError());
      return;
    }
    List<String> missing = preview.getVariables().stream()
        .filter(item -> "MISSING".equalsIgnoreCase(item.getSource()))
        .map(PriceLinkedFormulaPreviewResponse.VariableDetail::getName).toList();
    if (!missing.isEmpty()) {
      fail(formulaField, "当前月份缺少公式变量：" + String.join("、", missing));
      return;
    }
    List<String> blockingUnitWarnings = preview.getWarnings() == null ? List.of()
        : preview.getWarnings().stream()
            .filter(message -> message.contains("/1000") || message.contains("*1000"))
            .toList();
    if (!blockingUnitWarnings.isEmpty()) {
      fail(formulaField, "公式单位口径校验未通过：" + String.join("；", blockingUnitWarnings));
      return;
    }
    if (preview.getResult() == null || preview.getResult().compareTo(BigDecimal.ZERO) <= 0) {
      fail(formulaField, "公式当前取价结果必须大于0");
    }
  }

  private void validateWeightRelation(
      List<QuotePriceDraftField> fields, List<TechnicalVariable> variables) {
    TechnicalVariable blank = variables.stream()
        .filter(item -> normalizedField(item.sourceField()).equals("blankweight"))
        .findFirst().orElse(null);
    TechnicalVariable net = variables.stream()
        .filter(item -> normalizedField(item.sourceField()).equals("netweight"))
        .findFirst().orElse(null);
    if (blank == null || net == null) return;
    QuotePriceDraftField blankField = variableField(fields, blank);
    QuotePriceDraftField netField = variableField(fields, net);
    BigDecimal blankValue = decimalValue(blankField);
    BigDecimal netValue = decimalValue(netField);
    if (blankValue != null && netValue != null && netValue.compareTo(blankValue) > 0) {
      fail(netField, "净重不能大于下料重量");
    }
  }

  private Result result(List<QuotePriceDraftField> fields) {
    List<String> errors = fields.stream().filter(row -> "FAILED".equals(row.getValidationStatus()))
        .map(row -> row.getFieldName() + "：" + row.getValidationMessage()).distinct().toList();
    boolean valid = errors.isEmpty();
    return new Result(valid, valid ? "联动公式和本料号参数校验通过，等待统一提交"
        : String.join("；", errors), List.copyOf(fields));
  }

  private QuotePriceDraftField variableField(
      List<QuotePriceDraftField> fields, TechnicalVariable variable) {
    return fields.stream().filter(row -> "VARIABLE".equals(row.getSectionCode()))
        .filter(row -> matchesVariable(row, variable)).findFirst().orElse(null);
  }

  private static boolean matchesVariable(
      QuotePriceDraftField field, TechnicalVariable variable) {
    if (field.getFieldCode() == null) return false;
    String code = field.getFieldCode();
    return code.equalsIgnoreCase(variable.code())
        || code.equalsIgnoreCase(variable.sourceField())
        || variable.aliasCodes().stream().anyMatch(code::equalsIgnoreCase);
  }

  private QuotePriceDraftField field(
      List<QuotePriceDraftField> fields, String section, String code) {
    return fields.stream().filter(row -> section.equals(row.getSectionCode()))
        .filter(row -> code.equals(row.getFieldCode())).findFirst().orElse(null);
  }

  private QuotePriceDraftField newField(
      Long draftId, String section, String rowKey, String code, String name, String valueType,
      String reference, boolean required, boolean techInput, String unit, int sort) {
    QuotePriceDraftField field = new QuotePriceDraftField();
    field.setPriceDraftId(draftId);
    field.setSectionCode(section);
    field.setRowKey(rowKey);
    field.setFieldCode(code);
    field.setFieldName(name);
    field.setValueType(valueType);
    field.setReferenceValueJson(jsonValue(reference));
    field.setTargetValueJson(null);
    field.setUnit(unit);
    field.setRequiredFlag(required ? 1 : 0);
    field.setTechInputRequired(techInput ? 1 : 0);
    field.setChangedFlag(1);
    field.setValidationStatus("NOT_CHECKED");
    field.setSortSeq(sort);
    return field;
  }

  private BigDecimal decimal(QuotePriceDraftField field) {
    if (field == null || !StringUtils.hasText(field.getTargetValueJson())) return null;
    try {
      String value = jsonText(field.getTargetValueJson());
      return StringUtils.hasText(value) ? new BigDecimal(value.trim()) : null;
    } catch (RuntimeException exception) {
      fail(field, "请输入合法数字");
      return null;
    }
  }

  private BigDecimal decimalValue(QuotePriceDraftField field) {
    if (field == null || "FAILED".equals(field.getValidationStatus())) return null;
    try {
      String value = jsonText(field.getTargetValueJson());
      return StringUtils.hasText(value) ? new BigDecimal(value.trim()) : null;
    } catch (RuntimeException exception) {
      return null;
    }
  }

  private String jsonText(String json) {
    if (!StringUtils.hasText(json)) return null;
    try {
      JsonNode node = objectMapper.readTree(json);
      if (node == null || node.isNull()) return null;
      return node.isTextual() ? node.asText() : node.toString();
    } catch (Exception exception) {
      throw new IllegalArgumentException("草稿字段JSON格式错误", exception);
    }
  }

  private String jsonValue(String value) {
    if (value == null) return null;
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception exception) {
      throw new IllegalStateException("草稿字段序列化失败", exception);
    }
  }

  private void resetValidation(QuotePriceDraftField field) {
    field.setValidationStatus("PASSED");
    field.setValidationMessage(null);
  }

  private static void fail(QuotePriceDraftField field, String message) {
    if (field == null) return;
    field.setValidationStatus("FAILED");
    field.setValidationMessage(message);
  }

  private static boolean hasFailures(List<QuotePriceDraftField> fields) {
    return fields.stream().anyMatch(row -> "FAILED".equals(row.getValidationStatus()));
  }

  private static boolean isWeight(String sourceField) {
    return normalizedField(sourceField).endsWith("weight");
  }

  private static String normalizedField(String sourceField) {
    return sourceField == null ? ""
        : sourceField.toLowerCase(Locale.ROOT).replace("_", "");
  }

  private static String toCamel(String sourceField) {
    StringBuilder result = new StringBuilder();
    boolean upper = false;
    for (char ch : sourceField.toCharArray()) {
      if (ch == '_') {
        upper = true;
      } else {
        result.append(upper ? Character.toUpperCase(ch) : ch);
        upper = false;
      }
    }
    return result.toString();
  }

  private static String firstText(String... values) {
    for (String value : values) if (StringUtils.hasText(value)) return value.trim();
    return null;
  }

  private static String readableFormulaError(RuntimeException exception) {
    return exception instanceof FormulaSyntaxException
        ? "公式不符合现有变量和四则运算规则：" + safeMessage(exception)
        : safeMessage(exception);
  }

  private static String safeMessage(Throwable exception) {
    return StringUtils.hasText(exception.getMessage())
        ? exception.getMessage() : exception.getClass().getSimpleName();
  }

  private record TechnicalVariable(
      String code,
      String name,
      String sourceField,
      String propertyName,
      boolean weight,
      List<String> aliasCodes,
      int sort) {
    private String unit() {
      return weight ? "g" : "元/计价单位";
    }
  }

  public record Result(boolean valid, String message, List<QuotePriceDraftField> fields) {}
}
