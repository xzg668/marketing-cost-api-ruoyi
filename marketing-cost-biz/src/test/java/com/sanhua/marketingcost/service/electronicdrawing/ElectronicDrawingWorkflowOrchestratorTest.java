package com.sanhua.marketingcost.service.electronicdrawing;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("T1 电子图库独立产品级编排")
class ElectronicDrawingWorkflowOrchestratorTest {

  private ElectronicDrawingWorkflowContextPort contextPort;
  private DrawingVerificationService verifications;
  private com.sanhua.marketingcost.entity.DrawingVerificationTask verificationTask;
  private ElectronicDrawingSourceImportService importService;
  private ElectronicDrawingMaterialResolutionService resolutionService;
  private ElectronicDrawingHybridBomService hybridBomService;
  private ElectronicDrawingAutoPublicationService autoPublicationService;
  private AtomicReference<ElectronicDrawingWorkContext> current;
  private ElectronicDrawingWorkflowOrchestrator orchestrator;

  @BeforeEach
  void setUp() {
    contextPort = mock(ElectronicDrawingWorkflowContextPort.class);
    verifications = mock(DrawingVerificationService.class);
    verificationTask = acquired();
    importService = mock(ElectronicDrawingSourceImportService.class);
    resolutionService = mock(ElectronicDrawingMaterialResolutionService.class);
    hybridBomService = mock(ElectronicDrawingHybridBomService.class);
    autoPublicationService = mock(ElectronicDrawingAutoPublicationService.class);
    current = new AtomicReference<>(context(1, null, null, "WAIT_TECH", true));
    when(contextPort.load(101L, "COMMERCIAL", "210", "2026-08"))
        .thenAnswer(invocation -> current.get());
    when(contextPort.updateStage(any(), anyString(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              ElectronicDrawingWorkContext before = invocation.getArgument(0);
              ElectronicDrawingWorkContext next =
                  copy(
                      before,
                      before.revision() + 1,
                      before.sourceVersionId(),
                      invocation.getArgument(1),
                      before.workflowStatus(),
                      before.active(),
                      invocation.getArgument(2),
                      invocation.getArgument(3),
                      before.compositionFingerprint());
              current.set(next);
              return next;
            });
    when(verifications.current(any())).thenAnswer(call -> java.util.Optional.of(verificationTask));
    when(importService.importSource(any(), any()))
        .thenAnswer(
            invocation -> {
              ElectronicDrawingWorkContext before = current.get();
              current.set(
                  copy(
                      before,
                      before.revision() + 1,
                      201L,
                      before.workflowStage(),
                      before.workflowStatus(),
                      before.active(),
                      before.assigneeUserId(),
                      before.assigneeName(),
                      before.compositionFingerprint()));
              verificationTask.setConsumedResultId(verificationTask.getCurrentResultId());
              return new ElectronicDrawingSourceImportService.ImportResult(
                  201L, 1, "DRAFT", 40, 202L, false, true);
            });
    when(hybridBomService.compose(101L, "COMMERCIAL", "210", "2026-08"))
        .thenReturn(composition(28));
    when(autoPublicationService.publish(101L, "COMMERCIAL", "210", "2026-08"))
        .thenAnswer(
            invocation -> {
              ElectronicDrawingWorkContext before = current.get();
              ElectronicDrawingWorkContext published =
                  copy(
                      before,
                      before.revision() + 1,
                      before.sourceVersionId(),
                      before.workflowStage(),
                      "READY_FOR_COSTING",
                      false,
                      before.assigneeUserId(),
                      before.assigneeName(),
                      "FP");
              current.set(published);
              return new ElectronicDrawingAutoPublicationService.PublicationResult(
                  published, "SUPPLEMENT_VERSION:201", false);
            });
    orchestrator =
        new ElectronicDrawingWorkflowOrchestrator(
            contextPort,
            mock(QuoteProductBomPreparationService.class),
            verifications,
            mock(com.sanhua.marketingcost.mapper.DrawingVerificationTaskMapper.class),
            importService,
            resolutionService,
            hybridBomService,
            autoPublicationService);
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
    verify(verifications, never()).start(any(), any());
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
  void reviewWaitIsStableAndNeverImportsOrPosts() {
    verificationTask.setTaskState("WAIT_REVIEW");
    var result = orchestrator.process(command());
    assertThat(result.stage()).isEqualTo(ElectronicDrawingWorkflowStage.WAIT_REVIEW);
    verify(importService, never()).importSource(any(), any());
    verify(verifications, never()).start(any(), any());
  }

  @Test
  void failedVerificationDoesNotScheduleAnotherPost() {
    verificationTask.setTaskState("FAILED");
    var result = orchestrator.process(command());
    assertThat(result.stage()).isEqualTo(ElectronicDrawingWorkflowStage.FAILED);
    assertThat(result.costingCanContinue()).isFalse();
    verify(importService, never()).importSource(any(), any());
    verify(verifications, never()).start(any(), any());
  }

  @Test
  void retryAfterImportDoesNotAcquireAgain() {
    verificationTask.setConsumedResultId(202L);
    current.set(context(3, 201L, ElectronicDrawingWorkflowStage.RETRY, "WAIT_TECH", true));
    when(resolutionService.autoMatch(101L, "COMMERCIAL", "210", "2026-08"))
        .thenReturn(resolution(true, 0, 0));

    var result = orchestrator.process(command());

    assertThat(result.quotationLeafCount()).isEqualTo(28);
    verify(verifications, never()).start(any(), any());
    verify(importService, never()).importSource(any(), any());
  }

  @Test
  void composedReplayOnlyPublishes() {
    verificationTask.setConsumedResultId(202L);
    current.set(context(3, 201L, ElectronicDrawingWorkflowStage.COMPOSED, "WAIT_TECH", true));

    var result = orchestrator.process(command());

    assertThat(result.complete()).isTrue();
    verify(autoPublicationService).publish(101L, "COMMERCIAL", "210", "2026-08");
    verify(verifications, never()).start(any(), any());
    verify(resolutionService, never()).autoMatch(anyLong(), anyString(), anyString(), anyString());
  }

  @Test
  void manualSelectionCompletionContinuesWithoutAcquisition() {
    verificationTask.setConsumedResultId(202L);
    current.set(
        context(3, 201L, ElectronicDrawingWorkflowStage.MAPPING_PENDING, "WAIT_TECH", true));
    when(resolutionService.autoMatch(101L, "COMMERCIAL", "210", "2026-08"))
        .thenReturn(resolution(true, 0, 0));

    var result = orchestrator.resumeAfterMaterialSelection(101L, "COMMERCIAL", "210", "2026-08");

    assertThat(result.stage()).isEqualTo(ElectronicDrawingWorkflowStage.PUBLISHED);
    assertThat(result.quotationLeafCount()).isEqualTo(28);
    verify(verifications, never()).start(any(), any());
  }

  @Test
  void missingDrawingWeightReturnsActionableGapAndNeverPublishes() {
    verificationTask.setConsumedResultId(202L);
    current.set(
        context(3, 201L, ElectronicDrawingWorkflowStage.MAPPING_PENDING, "WAIT_TECH", true));
    when(resolutionService.autoMatch(101L, "COMMERCIAL", "210", "2026-08"))
        .thenReturn(resolution(true, 0, 0));
    when(hybridBomService.compose(101L, "COMMERCIAL", "210", "2026-08"))
        .thenThrow(
            new ElectronicDrawingHybridBomException(
                ElectronicDrawingHybridBomException.BOM_GAP, "电子图库按重量计价的物料缺少有效单重：RFK-E04-064011"));

    var result = orchestrator.resumeAfterMaterialSelection(101L, "COMMERCIAL", "210", "2026-08");

    assertThat(result.stage()).isEqualTo(ElectronicDrawingWorkflowStage.VALIDATION_FAILED);
    assertThat(result.message()).contains("缺少有效单重", "RFK-E04-064011");
    assertThat(result.costingCanContinue()).isFalse();
    verify(autoPublicationService, never())
        .publish(anyLong(), anyString(), anyString(), anyString());
  }

  private ElectronicDrawingWorkflowOrchestrator.WorkflowCommand command() {
    return new ElectronicDrawingWorkflowOrchestrator.WorkflowCommand(
        101L, 11L, "COMMERCIAL", "210", "J40AH-40HY-03", 8L, "报价员", "2026-08");
  }

  private ElectronicDrawingWorkContext context(
      int revision, Long sourceVersionId, String stage, String status, boolean active) {
    return new ElectronicDrawingWorkContext(
        101L,
        revision,
        301L,
        sourceVersionId,
        10L,
        11L,
        "PT-101",
        "OA-101",
        "1053100052030",
        null,
        "产品",
        null,
        null,
        null,
        "FULL_BOM",
        "2026-08",
        "210",
        "320",
        "COMMERCIAL",
        "210",
        active,
        true,
        status,
        stage,
        null,
        null,
        null);
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
        value.workflowId(),
        revision,
        value.preparationId(),
        sourceVersionId,
        value.oaFormId(),
        value.oaFormItemId(),
        value.taskNo(),
        value.oaNo(),
        value.quoteProductCode(),
        value.temporaryProductKey(),
        value.productName(),
        value.productSpec(),
        value.productModel(),
        value.productType(),
        value.primaryScope(),
        value.accountingMonth(),
        value.priceOrgCode(),
        value.materialOrgCode(),
        value.businessUnitType(),
        value.applicableOrgCode(),
        active,
        value.bomRequired(),
        status,
        stage,
        assigneeUserId,
        assigneeName,
        fingerprint);
  }

  private com.sanhua.marketingcost.entity.DrawingVerificationTask acquired() {
    var task = new com.sanhua.marketingcost.entity.DrawingVerificationTask();
    task.setId(200L);
    task.setCurrentResultId(202L);
    task.setTaskState("RECEIVED");
    task.setExternalTaskId("test-task");
    task.setConsumptionState("PENDING");
    return task;
  }

  private ElectronicDrawingMaterialResolutionResponse resolution(
      boolean complete, int unmatched, int ambiguous) {
    return new ElectronicDrawingMaterialResolutionResponse(
        101L,
        current.get().revision(),
        201L,
        1,
        "DRAFT",
        "J40AH-40HY-03",
        "320",
        40,
        complete ? 40 : 37,
        0,
        unmatched,
        ambiguous,
        complete,
        List.of(),
        "2026-08",
        current.get().workflowStage(),
        false,
        false);
  }

  private CompositionResult composition(int leafCount) {
    return new CompositionResult(
        101L,
        current.get().revision(),
        201L,
        1,
        "FP",
        47,
        18,
        10,
        leafCount,
        12,
        18,
        false,
        List.of());
  }
}
