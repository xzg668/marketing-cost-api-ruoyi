package com.sanhua.marketingcost.service.electronicdrawing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.electronicdrawing.ElectronicDrawingMaterialResolutionResponse;
import com.sanhua.marketingcost.service.QuoteProductBomPreparationService;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingHybridBomService.CompositionResult;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("T1 电子图库独立产品级编排")
class ElectronicDrawingWorkflowOrchestratorTest {

  private ElectronicDrawingWorkflowContextPort contextPort;
  private ElectronicDrawingExcelAcquisitionPort acquisitionPort;
  private ElectronicDrawingSourceImportService importService;
  private ElectronicDrawingMaterialResolutionService resolutionService;
  private ElectronicDrawingHybridBomService hybridBomService;
  private ElectronicDrawingAutoPublicationService autoPublicationService;
  private AtomicReference<ElectronicDrawingWorkContext> current;
  private ElectronicDrawingWorkflowOrchestrator orchestrator;

  @BeforeEach
  void setUp() {
    contextPort = mock(ElectronicDrawingWorkflowContextPort.class);
    acquisitionPort = mock(ElectronicDrawingExcelAcquisitionPort.class);
    importService = mock(ElectronicDrawingSourceImportService.class);
    resolutionService = mock(ElectronicDrawingMaterialResolutionService.class);
    hybridBomService = mock(ElectronicDrawingHybridBomService.class);
    autoPublicationService = mock(ElectronicDrawingAutoPublicationService.class);
    current = new AtomicReference<>(context(1, null, null, "WAIT_TECH", true));
    when(contextPort.load(101L, "COMMERCIAL", "210", "2026-08"))
        .thenAnswer(invocation -> current.get());
    when(contextPort.updateStage(any(), anyString(), any(), any(), any()))
        .thenAnswer(invocation -> {
          ElectronicDrawingWorkContext before = invocation.getArgument(0);
          ElectronicDrawingWorkContext next = copy(
              before, before.revision() + 1, before.sourceVersionId(),
              invocation.getArgument(1), before.workflowStatus(), before.active(),
              invocation.getArgument(2), invocation.getArgument(3),
              before.compositionFingerprint());
          current.set(next);
          return next;
        });
    when(acquisitionPort.acquire(any())).thenReturn(acquired());
    when(importService.importSource(any(), any())).thenAnswer(invocation -> {
      ElectronicDrawingWorkContext before = current.get();
      current.set(copy(before, before.revision() + 1, 201L, before.workflowStage(),
          before.workflowStatus(), before.active(), before.assigneeUserId(),
          before.assigneeName(), before.compositionFingerprint()));
      return new ElectronicDrawingSourceImportService.ImportResult(
          201L, 1, "DRAFT", 40, "SHA", false, true);
    });
    when(hybridBomService.compose(101L, "COMMERCIAL", "210", "2026-08"))
        .thenReturn(composition(28));
    when(autoPublicationService.publish(101L, "COMMERCIAL", "210", "2026-08"))
        .thenAnswer(invocation -> {
          ElectronicDrawingWorkContext before = current.get();
          ElectronicDrawingWorkContext published = copy(
              before, before.revision() + 1, before.sourceVersionId(), before.workflowStage(),
              "READY_FOR_COSTING", false, before.assigneeUserId(), before.assigneeName(), "FP");
          current.set(published);
          return new ElectronicDrawingAutoPublicationService.PublicationResult(
              published, "SUPPLEMENT_VERSION:201", false);
        });
    orchestrator = new ElectronicDrawingWorkflowOrchestrator(
        contextPort, mock(QuoteProductBomPreparationService.class), acquisitionPort,
        importService, resolutionService, hybridBomService, autoPublicationService);
  }

  @Test
  void autoMatchedFlowComposesTwentyEightLeaves() {
    when(resolutionService.autoMatch(101L, "COMMERCIAL", "210", "2026-08"))
        .thenReturn(resolution(true, 0, 0));

    var result = orchestrator.process(command());

    assertThat(result.stage()).isEqualTo(ElectronicDrawingWorkflowStage.PUBLISHED);
    assertThat(result.costingCanContinue()).isTrue();
    assertThat(result.quotationLeafCount()).isEqualTo(28);
    assertThat(current.get().workflowStage()).isEqualTo(ElectronicDrawingWorkflowStage.COMPOSED);
    verify(acquisitionPort, times(1)).acquire(any());
    verify(importService, times(1)).importSource(any(), any());
    verify(hybridBomService).compose(101L, "COMMERCIAL", "210", "2026-08");
  }

  @Test
  void unresolvedMaterialsWaitForFinanceSelection() {
    when(resolutionService.autoMatch(101L, "COMMERCIAL", "210", "2026-08"))
        .thenReturn(resolution(false, 1, 2));

    var result = orchestrator.process(command());

    assertThat(result.stage()).isEqualTo(ElectronicDrawingWorkflowStage.MAPPING_PENDING);
    assertThat(result.message()).contains("3 个物料");
    assertThat(current.get().assigneeUserId()).isEqualTo(8L);
    assertThat(current.get().assigneeName()).isEqualTo("报价员");
    verify(hybridBomService, never()).compose(anyLong(), anyString(), anyString(), anyString());
  }

  @Test
  void notFoundIsStableBusinessState() {
    when(acquisitionPort.acquire(any())).thenThrow(new ElectronicDrawingExcelAcquisitionException(
        ElectronicDrawingExcelAcquisitionException.BOM_NOT_FOUND, false,
        "HTTP 404 upstream payload"));

    var result = orchestrator.process(command());

    assertThat(result.stage()).isEqualTo(ElectronicDrawingWorkflowStage.NOT_FOUND);
    assertThat(result.message()).doesNotContain("HTTP", "404", "upstream");
    verify(importService, never()).importSource(any(), any());
  }

  @Test
  void retryableFailureSchedulesBackgroundRetry() {
    when(acquisitionPort.acquire(any())).thenThrow(new ElectronicDrawingExcelAcquisitionException(
        ElectronicDrawingExcelAcquisitionException.QUERY_RETRY, true,
        "HTTP 503 socket timeout"));

    assertThatThrownBy(() -> orchestrator.process(command()))
        .isInstanceOf(ElectronicDrawingWorkflowRetryException.class)
        .hasMessage("电子图库正在后台自动重试，报价员无需处理")
        .hasMessageNotContaining("503");
    assertThat(current.get().workflowStage()).isEqualTo(ElectronicDrawingWorkflowStage.RETRY);
  }

  @Test
  void retryAfterImportDoesNotAcquireAgain() {
    current.set(context(3, 201L, ElectronicDrawingWorkflowStage.RETRY, "WAIT_TECH", true));
    when(resolutionService.autoMatch(101L, "COMMERCIAL", "210", "2026-08"))
        .thenReturn(resolution(true, 0, 0));

    var result = orchestrator.process(command());

    assertThat(result.quotationLeafCount()).isEqualTo(28);
    verify(acquisitionPort, never()).acquire(any());
    verify(importService, never()).importSource(any(), any());
  }

  @Test
  void composedReplayOnlyPublishes() {
    current.set(context(3, 201L, ElectronicDrawingWorkflowStage.COMPOSED, "WAIT_TECH", true));

    var result = orchestrator.process(command());

    assertThat(result.complete()).isTrue();
    verify(autoPublicationService).publish(101L, "COMMERCIAL", "210", "2026-08");
    verify(acquisitionPort, never()).acquire(any());
    verify(resolutionService, never()).autoMatch(anyLong(), anyString(), anyString(), anyString());
  }

  @Test
  void manualSelectionCompletionContinuesWithoutAcquisition() {
    current.set(context(3, 201L, ElectronicDrawingWorkflowStage.MAPPING_PENDING, "WAIT_TECH", true));
    when(resolutionService.autoMatch(101L, "COMMERCIAL", "210", "2026-08"))
        .thenReturn(resolution(true, 0, 0));

    var result = orchestrator.resumeAfterMaterialSelection(101L, "COMMERCIAL", "210", "2026-08");

    assertThat(result.stage()).isEqualTo(ElectronicDrawingWorkflowStage.PUBLISHED);
    assertThat(result.quotationLeafCount()).isEqualTo(28);
    verify(acquisitionPort, never()).acquire(any());
  }

  private ElectronicDrawingWorkflowOrchestrator.WorkflowCommand command() {
    return new ElectronicDrawingWorkflowOrchestrator.WorkflowCommand(
        101L, 11L, "COMMERCIAL", "210", "J40AH-40HY-03", 8L, "报价员", "2026-08");
  }

  private ElectronicDrawingWorkContext context(
      int revision, Long sourceVersionId, String stage, String status, boolean active) {
    return new ElectronicDrawingWorkContext(
        101L, revision, 301L, sourceVersionId, 10L, 11L, "PT-101", "OA-101",
        "1053100052030", null, "产品", null, null, null, "FULL_BOM", "2026-08",
        "210", "320", "COMMERCIAL", "210", active, true, status, stage,
        null, null, null);
  }

  private ElectronicDrawingWorkContext copy(
      ElectronicDrawingWorkContext value,
      int revision,
      Long sourceVersionId,
      String stage,
      String status,
      boolean active,
      Long assigneeUserId,
      String assigneeName,
      String fingerprint) {
    return new ElectronicDrawingWorkContext(
        value.workflowId(), revision, value.preparationId(), sourceVersionId,
        value.oaFormId(), value.oaFormItemId(), value.taskNo(), value.oaNo(),
        value.quoteProductCode(), value.temporaryProductKey(), value.productName(),
        value.productSpec(), value.productModel(), value.productType(), value.primaryScope(),
        value.accountingMonth(), value.priceOrgCode(), value.materialOrgCode(),
        value.businessUnitType(), value.applicableOrgCode(), active, value.bomRequired(),
        status, stage, assigneeUserId, assigneeName, fingerprint);
  }

  private ElectronicDrawingExcelAcquisitionPort.AcquiredExcel acquired() {
    return new ElectronicDrawingExcelAcquisitionPort.AcquiredExcel(
        new byte[] {1, 2, 3}, "drawing.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 3,
        "SHA", "J40AH-40HY-03", "ED:PT-101:J40AH-40HY-03", "MOCK",
        LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE));
  }

  private ElectronicDrawingMaterialResolutionResponse resolution(
      boolean complete, int unmatched, int ambiguous) {
    return new ElectronicDrawingMaterialResolutionResponse(
        101L, current.get().revision(), 201L, 1, "DRAFT", "J40AH-40HY-03", "320",
        40, complete ? 40 : 37, 0, unmatched, ambiguous, complete, List.of(), "2026-08", current.get().workflowStage(), false, false);
  }

  private CompositionResult composition(int leafCount) {
    return new CompositionResult(
        101L, current.get().revision(), 201L, 1, "FP", 47, 18, 10, leafCount,
        12, 18, false, List.of());
  }
}
