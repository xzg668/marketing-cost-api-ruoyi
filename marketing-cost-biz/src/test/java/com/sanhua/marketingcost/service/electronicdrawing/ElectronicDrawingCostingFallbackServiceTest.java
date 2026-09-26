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
  private final ElectronicDrawingCostingFallbackService service =
      new ElectronicDrawingCostingFallbackService(contextResolver, orchestrator, mock(ElectronicDrawingPreparationSource.class));

  @BeforeEach
  void setUp() {
    when(contextResolver.resolveWithExistingCostPeriod(any(), any(), any()))
        .thenReturn(new QuoteBomContext(
            "2026-08", "P-1",
            new ResolvedCustomerKey(
                "客户A", ResolvedCustomerKey.Source.OA_HEADER_CUSTOMER, null),
            "", new QuoteDataOrganization("210", "COMMERCIAL")));
  }

  @Test
  void noDrawingDoesNotStartElectronicWorkflow() {
    ElectronicDrawingCostingFallbackService.AttemptResult result =
        service.attempt(form(), item(null), "2026-08");

    assertThat(result.attempted()).isFalse();
    verify(orchestrator, never()).process(any());
  }

  @Test
  void publishedElectronicBomCanContinueTheSameCostingRun() {
    when(orchestrator.process(any())).thenReturn(
        new ElectronicDrawingWorkflowOrchestrator.WorkflowResult(
            10L, 4, 91L, ElectronicDrawingWorkflowStage.PUBLISHED,
            "电子图库BOM已发布", true, 12));

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
    when(orchestrator.process(any())).thenThrow(
        new ElectronicDrawingWorkflowRetryException("电子图库后台重试"));

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
