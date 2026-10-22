package com.sanhua.marketingcost.service.electronicdrawing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.service.ingest.QuoteBomContext;
import com.sanhua.marketingcost.service.ingest.QuoteBomContextResolver;
import com.sanhua.marketingcost.service.ingest.ResolvedCustomerKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ElectronicDrawingCostingFallbackServiceTest {
  private final QuoteBomContextResolver contextResolver = mock(QuoteBomContextResolver.class);
  private final ElectronicDrawingWorkflowOrchestrator orchestrator =
      mock(ElectronicDrawingWorkflowOrchestrator.class);
  private final ElectronicDrawingPreparationSource preparation =
      mock(ElectronicDrawingPreparationSource.class);
  private final DrawingVerificationService verifications = mock(DrawingVerificationService.class);
  private final ElectronicDrawingWorkflowContextPort contexts =
      mock(ElectronicDrawingWorkflowContextPort.class);
  private final ElectronicDrawingWorkContext work =
      new ElectronicDrawingWorkContext(
          10L,
          0,
          1L,
          null,
          1L,
          10L,
          "EDP-04",
          "OA-1",
          "P-1",
          null,
          "产品",
          null,
          "MODEL",
          "NON_BARE",
          "FULL_BOM",
          "2026-08",
          "210",
          "COMMERCIAL",
          "COMMERCIAL",
          "210",
          true,
          true,
          "NEED_TECH",
          null,
          null,
          null,
          null);
  private final ElectronicDrawingCostingFallbackService service =
      new ElectronicDrawingCostingFallbackService(
          contextResolver, orchestrator, preparation, verifications, contexts);

  @BeforeEach
  void setUp() {
    when(contexts.load(any(), any(), any(), any())).thenReturn(work);
    when(contextResolver.resolveWithExistingCostPeriod(any(), any(), any()))
        .thenReturn(
            new QuoteBomContext(
                "2026-08",
                "P-1",
                new ResolvedCustomerKey("客户A", ResolvedCustomerKey.Source.OA_HEADER_CUSTOMER, null),
                "",
                new QuoteDataOrganization("210", "COMMERCIAL")));
  }

  @Test
  void noCustomerDrawingStillUsesTheMasterLookupInVerificationService() {
    var task = new com.sanhua.marketingcost.entity.DrawingVerificationTask();
    task.setTaskState("WAIT_REVIEW");
    task.setDifferenceCount(2);
    when(verifications.start(work, null)).thenReturn(java.util.Optional.of(task));
    var result = service.attempt(form(), item(null), "2026-08");
    assertThat(result.attempted()).isTrue();
    assertThat(result.stage()).isEqualTo(ElectronicDrawingWorkflowStage.WAIT_REVIEW);
    assertThat(result.costingCanContinue()).isFalse();
    verify(verifications).start(work, null);
    verify(orchestrator, never()).process(any());
  }

  @Test
  void receivedResultContinuesThroughTheSharedBomConsumerWithVerifiedDrawing() {
    var task = new com.sanhua.marketingcost.entity.DrawingVerificationTask();
    task.setTaskState("RECEIVED");
    task.setDrawingNo("VERIFIED-MASTER-DRAWING");
    when(verifications.start(work, null)).thenReturn(java.util.Optional.of(task));
    when(orchestrator.process(any()))
        .thenReturn(
            new ElectronicDrawingWorkflowOrchestrator.WorkflowResult(
                10L, 1, 91L, ElectronicDrawingWorkflowStage.MAPPING_PENDING, "物料待选择", false, 0));
    var result = service.attempt(form(), item(null), "2026-08");
    assertThat(result.stage()).isEqualTo(ElectronicDrawingWorkflowStage.MAPPING_PENDING);
    assertThat(result.costingCanContinue()).isFalse();
    var command =
        ArgumentCaptor.forClass(ElectronicDrawingWorkflowOrchestrator.WorkflowCommand.class);
    verify(orchestrator).process(command.capture());
    assertThat(command.getValue().drawingNo()).isEqualTo("VERIFIED-MASTER-DRAWING");
  }

  @Test
  void composedSourceResumesAfterClassificationWithoutCustomerDrawing() {
    when(preparation.composedDrawingNo(10L, "2026-08", "COMMERCIAL", "210"))
        .thenReturn("TECH-CONFIRMED-DRAWING");
    when(orchestrator.process(any()))
        .thenReturn(
            new ElectronicDrawingWorkflowOrchestrator.WorkflowResult(
                10L, 4, 91L, ElectronicDrawingWorkflowStage.PUBLISHED, "电子图库BOM已发布", true, 12));

    var result = service.attempt(form(), item(null), "2026-08");

    assertThat(result.costingCanContinue()).isTrue();
    var command =
        ArgumentCaptor.forClass(ElectronicDrawingWorkflowOrchestrator.WorkflowCommand.class);
    verify(orchestrator).process(command.capture());
    assertThat(command.getValue().drawingNo()).isEqualTo("TECH-CONFIRMED-DRAWING");
    assertThat(command.getValue().accountingMonth()).isEqualTo("2026-08");
  }

  @Test
  void noRevisionDoesNotAcquireDrawingAgainWhenResumingPublishedBom() {
    var result = service.resumeComposed(form(), item("DRAW-001"), "2026-08");
    assertThat(result.attempted()).isFalse();
    verify(orchestrator, never()).process(any());
    verify(preparation, never()).prepareSharedDrawing(any(), any());
  }

  @Test
  void submittedRawRevisionResumesCompositionWithoutAcquiringAnotherSource() {
    when(preparation.composedDrawingNo(10L, "2026-08", "COMMERCIAL", "210"))
        .thenReturn("REVISION-DRAWING");
    when(orchestrator.process(any()))
        .thenReturn(
            new ElectronicDrawingWorkflowOrchestrator.WorkflowResult(
                10L, 5, 92L, ElectronicDrawingWorkflowStage.PUBLISHED, "修订已发布", true, 12));
    var result = service.resumeComposed(form(), item("OLD-DRAWING"), "2026-08");
    assertThat(result.costingCanContinue()).isTrue();
    var command =
        ArgumentCaptor.forClass(ElectronicDrawingWorkflowOrchestrator.WorkflowCommand.class);
    verify(orchestrator).process(command.capture());
    assertThat(command.getValue().drawingNo()).isEqualTo("REVISION-DRAWING");
    verify(preparation, never()).prepareSharedDrawing(any(), any());
  }

  @Test
  void publishedElectronicBomCanContinueTheSameCostingRun() {
    when(preparation.composedDrawingNo(10L, "2026-08", "COMMERCIAL", "210")).thenReturn("DRAW-001");
    when(orchestrator.process(any()))
        .thenReturn(
            new ElectronicDrawingWorkflowOrchestrator.WorkflowResult(
                10L, 4, 91L, ElectronicDrawingWorkflowStage.PUBLISHED, "电子图库BOM已发布", true, 12));

    ElectronicDrawingCostingFallbackService.AttemptResult result =
        service.attempt(form(), item("DRAW-001"), "2026-08");

    assertThat(result.costingCanContinue()).isTrue();
    ArgumentCaptor<ElectronicDrawingWorkflowOrchestrator.WorkflowCommand> command =
        ArgumentCaptor.forClass(ElectronicDrawingWorkflowOrchestrator.WorkflowCommand.class);
    verify(orchestrator).process(command.capture());
    assertThat(command.getValue().workflowId()).isEqualTo(10L);
    assertThat(command.getValue().oaFormItemId()).isEqualTo(10L);
    assertThat(command.getValue().applicableOrgCode()).isEqualTo("210");
    assertThat(command.getValue().drawingNo()).isEqualTo("DRAW-001");
  }

  @Test
  void retryIsPersistedAsWaitingInsteadOfFailingTheCostingRequest() {
    when(preparation.composedDrawingNo(10L, "2026-08", "COMMERCIAL", "210")).thenReturn("DRAW-001");
    when(orchestrator.process(any()))
        .thenThrow(new ElectronicDrawingWorkflowRetryException("电子图库后台重试"));

    ElectronicDrawingCostingFallbackService.AttemptResult result =
        service.attempt(form(), item("DRAW-001"), "2026-08");

    assertThat(result.attempted()).isTrue();
    assertThat(result.costingCanContinue()).isFalse();
    assertThat(result.stage()).isEqualTo(ElectronicDrawingWorkflowStage.RETRY);
  }

  private OaForm form() {
    OaForm form = new OaForm();
    form.setId(1L);
    form.setOaNo("OA-1");
    form.setBusinessUnitType("COMMERCIAL");
    return form;
  }

  private OaFormItem item(String drawing) {
    OaFormItem item = new OaFormItem();
    item.setId(10L);
    item.setOaFormId(1L);
    item.setBusinessUnitType("COMMERCIAL");
    item.setCustomerDrawing(drawing);
    return item;
  }
}
