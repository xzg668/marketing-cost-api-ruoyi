package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.collaboration.TechnicalPriceDraftCreateRequest;
import com.sanhua.marketingcost.dto.collaboration.TechnicalPriceDraftValidateRequest;
import com.sanhua.marketingcost.entity.QuoteCollaborationGap;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuotePriceDraft;
import com.sanhua.marketingcost.entity.QuotePriceDraftField;
import com.sanhua.marketingcost.mapper.BusinessChangeLogMapper;
import com.sanhua.marketingcost.mapper.QuoteCollaborationGapMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@DisplayName("QCBP-14 同一缺口价格草稿生命周期")
class TechnicalPriceDraftApplicationServiceTest {
  private final QuoteCollaborationTaskRepository taskRepository = mock(QuoteCollaborationTaskRepository.class);
  private final QuotePriceDraftRepository draftRepository = mock(QuotePriceDraftRepository.class);
  private final QuoteCollaborationGapMapper gapMapper = mock(QuoteCollaborationGapMapper.class);
  private final FormalPriceReferenceGateway referenceGateway = mock(FormalPriceReferenceGateway.class);
  private final CollaborationCurrentPrincipalProvider principalProvider = mock(CollaborationCurrentPrincipalProvider.class);
  private final BusinessChangeLogMapper changeLogMapper = mock(BusinessChangeLogMapper.class);
  private final FixedPriceDraftValidator fixedPriceValidator = mock(FixedPriceDraftValidator.class);
  private final LinkedPriceDraftValidator linkedPriceValidator = mock(LinkedPriceDraftValidator.class);
  private final RangePriceDraftValidator rangePriceValidator = mock(RangePriceDraftValidator.class);
  private final TechnicalPriceDraftApplicationService service = new TechnicalPriceDraftApplicationService(
      taskRepository, draftRepository, gapMapper, referenceGateway, principalProvider,
      changeLogMapper, new ObjectMapper(), new CollaborationIdempotency(), fixedPriceValidator,
      linkedPriceValidator, rangePriceValidator);
  private final CollaborationPrincipal technician = new CollaborationPrincipal(
      601L, "王工", Set.of(CollaborationRole.TECHNICIAN));
  private final CollaborationScope scope = new CollaborationScope("COMMERCIAL", "210");
  private QuoteCollaborationProductTask task;
  private QuoteCollaborationGap gap;

  @BeforeEach
  void setup() {
    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken("wang", null, List.of());
    auth.setDetails(Map.of("businessUnitType", "COMMERCIAL"));
    SecurityContextHolder.getContext().setAuthentication(auth);
    when(principalProvider.currentTechnician()).thenReturn(technician);
    task = new QuoteCollaborationProductTask();
    task.setId(11L);
    task.setProductCode("P-1");
    task.setBusinessUnitType("COMMERCIAL");
    task.setApplicableOrgCode("210");
    task.setAccountingMonth("2026-08");
    task.setCurrentAssigneeUserId(601L);
    gap = new QuoteCollaborationGap();
    gap.setId(21L);
    gap.setProductTaskId(11L);
    gap.setGapCategory("PRICE");
    gap.setGapStatus("OPEN");
    gap.setMaterialCode("RAW-1");
    when(gapMapper.selectById(21L)).thenReturn(gap);
    when(gapMapper.selectScopedForUpdateById(21L, "COMMERCIAL", "210")).thenReturn(gap);
    when(taskRepository.findMineById(11L, 601L, "COMMERCIAL")).thenReturn(Optional.of(task));
    when(gapMapper.bindCurrentPriceDraft(any(), any(), any(), any(), any(), any())).thenReturn(1);
    when(draftRepository.saveDraft(any())).thenAnswer(invocation -> {
      QuotePriceDraft draft = invocation.getArgument(0);
      draft.setId(31L);
      draft.setDraftNo("QCPD-31");
      return draft;
    });
    when(draftRepository.findFields(31L, scope)).thenReturn(List.of());
  }

  @AfterEach
  void cleanup() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void doubleOpenReturnsTheSameCurrentDraftInsteadOfCreatingAnother() {
    service.create(21L, new TechnicalPriceDraftCreateRequest("FIXED_PURCHASE", null, null));
    QuotePriceDraft existing = new QuotePriceDraft();
    existing.setId(31L);
    existing.setDraftNo("QCPD-31");
    existing.setDraftVersion(1);
    existing.setGapId(21L);
    existing.setProductTaskId(11L);
    existing.setBusinessUnitType("COMMERCIAL");
    existing.setOrgCode("210");
    existing.setPriceType("FIXED_PURCHASE");
    existing.setSourceMode("DIRECT");
    existing.setDraftStatus("EDITING");
    gap.setCurrentPriceDraftId(31L);
    when(draftRepository.findById(31L, scope)).thenReturn(Optional.of(existing));

    assertThat(service.create(
        21L, new TechnicalPriceDraftCreateRequest("LINKED", null, null)).draftId()).isEqualTo(31L);
    verify(draftRepository, times(1)).saveDraft(any());
  }

  @Test
  void copiedFormulaKeepsReferenceButClearsTargetSpecificTechnicalVariables() {
    FormalPriceReference reference = new FormalPriceReference(
        "lp_price_linked_item", 91L, "LINKED", "SIM-1", "相似物料", "TP2", "210",
        null, null, "kg", 1, "0.13", null, null, "公式", "2026-08",
        List.of(
            new FormalPriceReference.Field("FORMULA", "MAIN", "FORMULA_EXPR", "公式", "TEXT",
                "[Cu]*[net_weight]", null, true, false, 10),
            new FormalPriceReference.Field("VARIABLE", "MAIN", "net_weight", "净重", "DECIMAL",
                "1.5", "g", true, true, 20)));
    when(referenceGateway.findEffective(
        "COMMERCIAL", "210", "2026-08", "lp_price_linked_item", 91L))
        .thenReturn(Optional.of(reference));

    service.create(21L, new TechnicalPriceDraftCreateRequest(null, "lp_price_linked_item", 91L));

    ArgumentCaptor<QuotePriceDraft> draft = ArgumentCaptor.forClass(QuotePriceDraft.class);
    verify(draftRepository).saveDraft(draft.capture());
    assertThat(draft.getValue().getEffectiveFrom()).isEqualTo(java.time.LocalDate.of(2026, 8, 1));

    ArgumentCaptor<List> fields = ArgumentCaptor.forClass(List.class);
    verify(draftRepository).saveFields(fields.capture());
    assertThat(fields.getValue()).hasSize(2);
    assertThat(fields.getValue().get(0).toString()).isNotBlank();
    com.sanhua.marketingcost.entity.QuotePriceDraftField formula =
        (com.sanhua.marketingcost.entity.QuotePriceDraftField) fields.getValue().get(0);
    com.sanhua.marketingcost.entity.QuotePriceDraftField variable =
        (com.sanhua.marketingcost.entity.QuotePriceDraftField) fields.getValue().get(1);
    assertThat(formula.getReferenceValueJson()).isEqualTo("\"[Cu]*[net_weight]\"");
    assertThat(formula.getTargetValueJson()).isEqualTo("\"[Cu]*[net_weight]\"");
    assertThat(variable.getReferenceValueJson()).isEqualTo("\"1.5\"");
    assertThat(variable.getTargetValueJson()).isNull();
  }

  @Test
  void directDraftDefaultsToTheCurrentAccountingMonth() {
    service.create(21L, new TechnicalPriceDraftCreateRequest("FIXED_PURCHASE", null, null));

    ArgumentCaptor<QuotePriceDraft> draft = ArgumentCaptor.forClass(QuotePriceDraft.class);
    verify(draftRepository).saveDraft(draft.capture());
    assertThat(draft.getValue().getEffectiveFrom()).isEqualTo(java.time.LocalDate.of(2026, 8, 1));
  }

  @Test
  void fixedDraftBecomesReadyOnlyAfterSuccessfulValidation() {
    QuotePriceDraft existing = existingFixedDraft();
    QuotePriceDraft passed = existingFixedDraft();
    passed.setDraftVersion(2);
    passed.setValidationStatus("PASSED");
    passed.setValidationMessage("校验通过，等待统一提交");
    QuotePriceDraftField amount = new QuotePriceDraftField();
    amount.setPriceDraftId(31L);
    amount.setSectionCode("COMMON");
    amount.setRowKey("MAIN");
    amount.setFieldCode("PRICE");
    amount.setFieldName("固定单价");
    amount.setTargetValueJson("\"100\"");
    amount.setSortSeq(10);
    gap.setCurrentPriceDraftId(31L);
    when(gapMapper.selectList(any())).thenReturn(List.of(gap));
    when(draftRepository.findById(31L, scope))
        .thenReturn(Optional.of(existing), Optional.of(existing));
    when(draftRepository.findFields(31L, scope)).thenReturn(List.of(amount));
    when(fixedPriceValidator.validate(any(), any(), any())).thenReturn(
        new FixedPriceDraftValidator.Result(true, "校验通过，等待统一提交",
            List.of(amount), new FixedPriceDraftValidator.TaxConversion("100", "88.495575")));
    when(draftRepository.updateValidation(
        31L, 1, "PASSED", "校验通过，等待统一提交", scope, technician.actor()))
        .thenReturn(passed);
    when(gapMapper.updatePriceDraftValidationStatus(
        21L, 31L, "DRAFT_READY", "COMMERCIAL", "210", 601L, "王工"))
        .thenReturn(1);

    assertThat(service.validate(31L, new TechnicalPriceDraftValidateRequest(1)).validationStatus())
        .isEqualTo("PASSED");
    verify(draftRepository).replaceEditableFields(31L, List.of(amount), scope);
    verify(gapMapper).updatePriceDraftValidationStatus(
        21L, 31L, "DRAFT_READY", "COMMERCIAL", "210", 601L, "王工");
  }

  @Test
  void linkedDraftUsesLinkedValidatorAndBecomesReadyOnlyAfterFormulaPasses() {
    QuotePriceDraft existing = existingLinkedDraft();
    QuotePriceDraft passed = existingLinkedDraft();
    passed.setDraftVersion(2);
    passed.setValidationStatus("PASSED");
    passed.setValidationMessage("联动公式和本料号参数校验通过，等待统一提交");
    QuotePriceDraftField formula = new QuotePriceDraftField();
    formula.setPriceDraftId(31L);
    formula.setSectionCode("FORMULA");
    formula.setRowKey("MAIN");
    formula.setFieldCode("FORMULA_EXPR");
    formula.setFieldName("联动公式");
    formula.setTargetValueJson("\"[factor_identity_191]+[process_fee]\"");
    formula.setSortSeq(10);
    gap.setCurrentPriceDraftId(31L);
    when(gapMapper.selectList(any())).thenReturn(List.of(gap));
    when(draftRepository.findById(31L, scope))
        .thenReturn(Optional.of(existing), Optional.of(existing));
    when(draftRepository.findFields(31L, scope)).thenReturn(List.of(formula));
    when(linkedPriceValidator.validate(any(), any(), any())).thenReturn(
        new LinkedPriceDraftValidator.Result(true,
            "联动公式和本料号参数校验通过，等待统一提交", List.of(formula)));
    when(draftRepository.updateValidation(
        31L, 1, "PASSED", "联动公式和本料号参数校验通过，等待统一提交",
        scope, technician.actor())).thenReturn(passed);
    when(gapMapper.updatePriceDraftValidationStatus(
        21L, 31L, "DRAFT_READY", "COMMERCIAL", "210", 601L, "王工"))
        .thenReturn(1);

    assertThat(service.validate(31L, new TechnicalPriceDraftValidateRequest(1)).validationStatus())
        .isEqualTo("PASSED");
    verify(linkedPriceValidator).validate(any(), any(), any());
    verify(fixedPriceValidator, never()).validate(any(), any(), any());
    verify(draftRepository).replaceEditableFields(31L, List.of(formula), scope);
    verify(gapMapper).updatePriceDraftValidationStatus(
        21L, 31L, "DRAFT_READY", "COMMERCIAL", "210", 601L, "王工");
  }

  private QuotePriceDraft existingFixedDraft() {
    QuotePriceDraft draft = new QuotePriceDraft();
    draft.setId(31L);
    draft.setDraftNo("QCPD-31");
    draft.setDraftVersion(1);
    draft.setGapId(21L);
    draft.setProductTaskId(11L);
    draft.setBusinessUnitType("COMMERCIAL");
    draft.setOrgCode("210");
    draft.setMaterialCode("RAW-1");
    draft.setPriceType("FIXED_PURCHASE");
    draft.setSourceMode("DIRECT");
    draft.setDraftStatus("EDITING");
    draft.setValidationStatus("NOT_CHECKED");
    draft.setTaxIncluded(1);
    draft.setTaxRate(new java.math.BigDecimal("0.13"));
    return draft;
  }

  private QuotePriceDraft existingLinkedDraft() {
    QuotePriceDraft draft = existingFixedDraft();
    draft.setPriceType("LINKED");
    draft.setTaxIncluded(0);
    draft.setTaxRate(null);
    return draft;
  }
}
