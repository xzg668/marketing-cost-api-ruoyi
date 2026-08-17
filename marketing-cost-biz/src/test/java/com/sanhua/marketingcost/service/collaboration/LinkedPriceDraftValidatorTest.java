package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.sanhua.marketingcost.formula.registry.RowLocalPlaceholderRegistry;
import com.sanhua.marketingcost.mapper.PriceVariableMapper;
import com.sanhua.marketingcost.service.PriceLinkedFormulaPreviewService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("QCBP-16 联动价公式与目标技术变量校验")
class LinkedPriceDraftValidatorTest {
  private final FormulaNormalizer normalizer = mock(FormulaNormalizer.class);
  private final FormulaValidator formulaValidator = mock(FormulaValidator.class);
  private final FormulaDisplayRenderer displayRenderer = mock(FormulaDisplayRenderer.class);
  private final PriceLinkedFormulaPreviewService previewService =
      mock(PriceLinkedFormulaPreviewService.class);
  private final PriceVariableMapper variableMapper = mock(PriceVariableMapper.class);
  private final RowLocalPlaceholderRegistry placeholderRegistry =
      mock(RowLocalPlaceholderRegistry.class);
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final LinkedPriceDraftValidator validator = new LinkedPriceDraftValidator(
      normalizer, formulaValidator, displayRenderer, previewService, variableMapper,
      placeholderRegistry, objectMapper);

  @BeforeEach
  void setup() {
    when(normalizer.normalize(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(displayRenderer.renderCn(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(placeholderRegistry.tokenNames()).thenReturn(Map.of());
    when(placeholderRegistry.isKnown(any())).thenReturn(false);
  }

  @Test
  void onlyReferencedTechnicalVariablesAreShownAndMustUseTargetValues() {
    String formula = "[Cu]*[net_weight]+[process_fee]";
    when(variableMapper.selectList(any())).thenReturn(List.of(
        variable("Cu", "铜价", "FINANCE_FACTOR", "FACTOR", "price"),
        variable("net_weight", "产品净重", "PART_CONTEXT", "LINKED_ITEM", "net_weight"),
        variable("process_fee", "加工费", "PART_CONTEXT", "LINKED_ITEM", "process_fee")));
    when(previewService.previewForRefresh(any(), any(Map.class))).thenReturn(successPreview());

    List<QuotePriceDraftField> initial = List.of(
        field("FORMULA", "FORMULA_EXPR", "联动公式", formula, formula),
        field("VARIABLE", "blank_weight", "下料重量", null, "88"),
        field("VARIABLE", "net_weight", "产品净重", "12", null),
        field("VARIABLE", "process_fee", "加工费", "3", null));
    LinkedPriceDraftValidator.Result missing = validator.validate(task(), draft(), initial);

    assertThat(missing.valid()).isFalse();
    assertThat(missing.fields()).filteredOn(row -> "VARIABLE".equals(row.getSectionCode()))
        .extracting(QuotePriceDraftField::getFieldCode)
        .containsExactly("net_weight", "process_fee");
    assertThat(missing.fields()).filteredOn(row -> "VARIABLE".equals(row.getSectionCode()))
        .allMatch(row -> row.getTargetValueJson() == null)
        .allMatch(row -> "FAILED".equals(row.getValidationStatus()));

    List<QuotePriceDraftField> completed = new ArrayList<>(missing.fields());
    target(completed, "net_weight", "10");
    target(completed, "process_fee", "2.5");
    LinkedPriceDraftValidator.Result passed = validator.validate(task(), draft(), completed);

    assertThat(passed.valid()).isTrue();
    ArgumentCaptor<PriceLinkedItem> item = ArgumentCaptor.forClass(PriceLinkedItem.class);
    verify(previewService).previewForRefresh(item.capture(), any(Map.class));
    assertThat(item.getValue().getMaterialCode()).isEqualTo("Q16-TARGET");
    assertThat(item.getValue().getNetWeight()).isEqualByComparingTo("10");
    assertThat(item.getValue().getProcessFee()).isEqualByComparingTo("2.5");
  }

  @Test
  void rejectsUnknownFormulaUnitWarningsAndInvalidTargetRanges() {
    when(variableMapper.selectList(any())).thenReturn(List.of(
        variable("blank_weight", "下料重量", "PART_CONTEXT", "LINKED_ITEM", "blank_weight"),
        variable("net_weight", "产品净重", "PART_CONTEXT", "LINKED_ITEM", "net_weight")));
    FormulaSyntaxException unknown = new FormulaSyntaxException("未知变量 [bad]");
    org.mockito.Mockito.doThrow(unknown).when(formulaValidator).validate("[bad]+1");
    LinkedPriceDraftValidator.Result unknownResult = validator.validate(
        task(), draft(), List.of(field("FORMULA", "FORMULA_EXPR", "联动公式", "[bad]+1", "[bad]+1")));
    assertThat(unknownResult.message()).contains("未知变量 [bad]");

    PriceLinkedFormulaPreviewResponse warning = successPreview();
    warning.getWarnings().add("公式里出现 /1000，重量已自动换算");
    when(previewService.previewForRefresh(any(), any(Map.class))).thenReturn(warning);
    List<QuotePriceDraftField> rangeFields = new ArrayList<>(List.of(
        field("FORMULA", "FORMULA_EXPR", "联动公式",
            "[blank_weight]-[net_weight]", "[blank_weight]-[net_weight]"),
        field("VARIABLE", "blank_weight", "下料重量", null, "5"),
        field("VARIABLE", "net_weight", "产品净重", null, "6")));
    LinkedPriceDraftValidator.Result invalidWeight = validator.validate(task(), draft(), rangeFields);
    assertThat(invalidWeight.message()).contains("净重不能大于下料重量");

    target(rangeFields, "net_weight", "4");
    LinkedPriceDraftValidator.Result unitWarning = validator.validate(task(), draft(), rangeFields);
    assertThat(unitWarning.message()).contains("公式单位口径校验未通过");
  }

  @Test
  void rowLocalFactorBindingIsCopiedAsHiddenStructureForCurrentPriceValidation() {
    when(placeholderRegistry.tokenNames()).thenReturn(
        Map.of("__material", List.of("材料含税价格", "材料价格")));
    when(placeholderRegistry.isKnown("__material")).thenReturn(true);
    when(variableMapper.selectList(any())).thenReturn(List.of(
        variable("net_weight", "产品净重", "PART_CONTEXT", "LINKED_ITEM", "net_weight")));
    when(previewService.previewForRefresh(any(), any(Map.class))).thenReturn(successPreview());
    List<QuotePriceDraftField> fields = new ArrayList<>(List.of(
        field("FORMULA", "FORMULA_EXPR", "联动公式", "[__material]*[net_weight]",
            "[__material]*[net_weight]"),
        field("VARIABLE", "net_weight", "产品净重", null, "10"),
        binding("BIND-1", "TOKEN_NAME", "材料含税价格"),
        binding("BIND-1", "FACTOR_CODE", "factor_identity_191")));

    LinkedPriceDraftValidator.Result result = validator.validate(task(), draft(), fields);

    assertThat(result.valid()).isTrue();
    ArgumentCaptor<PriceLinkedItem> item = ArgumentCaptor.forClass(PriceLinkedItem.class);
    verify(previewService).previewForRefresh(item.capture(), any(Map.class));
    assertThat(item.getValue().getFormulaExpr())
        .isEqualTo("[factor_identity_191]*[net_weight]");
    assertThat(result.fields()).filteredOn(row -> "BINDING".equals(row.getSectionCode()))
        .hasSize(2);
  }

  private QuoteCollaborationProductTask task() {
    QuoteCollaborationProductTask task = new QuoteCollaborationProductTask();
    task.setBusinessUnitType("COMMERCIAL");
    task.setApplicableOrgCode("210");
    task.setAccountingMonth("2026-08");
    return task;
  }

  private QuotePriceDraft draft() {
    QuotePriceDraft draft = new QuotePriceDraft();
    draft.setId(31L);
    draft.setMaterialCode("Q16-TARGET");
    draft.setMaterialName("联动价目标物料");
    draft.setPriceType("LINKED");
    draft.setUnit("件");
    draft.setTaxIncluded(1);
    draft.setEffectiveFrom(LocalDate.of(2026, 8, 1));
    return draft;
  }

  private PriceVariable variable(
      String code, String name, String factorType, String sourceType, String sourceField) {
    PriceVariable variable = new PriceVariable();
    variable.setVariableCode(code);
    variable.setVariableName(name);
    variable.setFactorType(factorType);
    variable.setSourceType(sourceType);
    variable.setSourceField(sourceField);
    variable.setStatus("active");
    return variable;
  }

  private QuotePriceDraftField field(
      String section, String code, String name, String reference, String target) {
    QuotePriceDraftField field = new QuotePriceDraftField();
    field.setPriceDraftId(31L);
    field.setSectionCode(section);
    field.setRowKey("MAIN");
    field.setFieldCode(code);
    field.setFieldName(name);
    field.setValueType("TEXT");
    field.setReferenceValueJson(json(reference));
    field.setTargetValueJson(json(target));
    field.setRequiredFlag(1);
    field.setTechInputRequired(1);
    field.setSortSeq(section.equals("FORMULA") ? 10 : 30);
    return field;
  }

  private QuotePriceDraftField binding(String row, String code, String value) {
    QuotePriceDraftField field = field("BINDING", code, code, value, value);
    field.setRowKey(row);
    field.setTechInputRequired(0);
    field.setRequiredFlag(0);
    field.setSortSeq(1000);
    return field;
  }

  private void target(List<QuotePriceDraftField> fields, String code, String value) {
    fields.stream().filter(row -> code.equals(row.getFieldCode())).findFirst().orElseThrow()
        .setTargetValueJson(json(value));
  }

  private String json(String value) {
    if (value == null) return null;
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception exception) {
      throw new AssertionError(exception);
    }
  }

  private PriceLinkedFormulaPreviewResponse successPreview() {
    PriceLinkedFormulaPreviewResponse response = new PriceLinkedFormulaPreviewResponse();
    response.setResult(new BigDecimal("12.34"));
    return response;
  }
}
