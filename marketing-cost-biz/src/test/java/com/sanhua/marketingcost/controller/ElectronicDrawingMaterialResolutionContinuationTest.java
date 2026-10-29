package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.electronicdrawing.ElectronicDrawingBomCorrectionRequest;
import com.sanhua.marketingcost.dto.electronicdrawing.ElectronicDrawingMaterialResolutionRequest;
import com.sanhua.marketingcost.dto.electronicdrawing.ElectronicDrawingMaterialResolutionResponse;
import com.sanhua.marketingcost.service.ProductCostingPipeline;
import com.sanhua.marketingcost.service.electronicdrawing.*;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ElectronicDrawingMaterialResolutionContinuationTest {
  @Mock ElectronicDrawingMaterialResolutionService resolutions;
  @Mock ElectronicDrawingWorkflowOrchestrator workflow;
  @Mock ElectronicDrawingBomCorrectionService corrections;
  @Mock ElectronicDrawingWorkflowContextPort contexts;
  @Mock ProductCostingPipeline pipeline;
  ElectronicDrawingMaterialResolutionController controller;

  @BeforeEach
  void setup() {
    controller =
        new ElectronicDrawingMaterialResolutionController(
            resolutions, workflow, corrections, pipeline, contexts);
  }

  @ParameterizedTest
  @ValueSource(strings = {"E_DRAWING_COMPOSED", "E_DRAWING_PUBLISHED"})
  void confirmedMaterialsContinueToPriceAndDataChecksWithoutCreatingCost(String stage) {
    var request = new ElectronicDrawingMaterialResolutionRequest(4, 501L, java.util.List.of());
    var saved = state(true);
    when(resolutions.apply(11L, request, "2026-10")).thenReturn(saved);
    when(resolutions.state(11L, "2026-10")).thenReturn(saved);
    arrangeContinuation(stage);

    assertThat(controller.apply(11L, request, "2026-10").getData()).isEqualTo(saved);
    verifyPreparationOnly();
  }

  @Test
  void savingCorrectionAlsoContinuesButKeepsRealBomGapsBlocked() {
    var request =
        new ElectronicDrawingBomCorrectionRequest(4, 501L, 601L, "核实修正", java.util.List.of());
    var saved = state(true);
    when(corrections.save(11L, request, "2026-10")).thenReturn(saved);
    when(resolutions.state(11L, "2026-10")).thenReturn(saved);
    when(workflow.resumeAfterMaterialSelection(11L, "2026-10"))
        .thenReturn(
            new ElectronicDrawingWorkflowOrchestrator.WorkflowResult(
                11L, 5, 502L, "VALIDATION_FAILED", "子件缺单重", false, 0));

    assertThat(controller.correctBom(11L, request, "2026-10").getData()).isEqualTo(saved);
    verifyNoInteractions(pipeline, contexts);
  }

  @Test
  void finalCorrectionPreparesChildrenBeforeReturningSavedState() {
    var request =
        new ElectronicDrawingBomCorrectionRequest(4, 501L, 601L, "核实修正", java.util.List.of());
    var saved = state(true);
    when(corrections.save(11L, request, "2026-10")).thenReturn(saved);
    when(resolutions.state(11L, "2026-10")).thenReturn(saved);
    arrangeContinuation(ElectronicDrawingWorkflowStage.COMPOSED);

    assertThat(controller.correctBom(11L, request, "2026-10").getData()).isEqualTo(saved);
    verifyPreparationOnly();
  }

  private void arrangeContinuation(String stage) {
    when(workflow.resumeAfterMaterialSelection(11L, "2026-10"))
        .thenReturn(
            new ElectronicDrawingWorkflowOrchestrator.WorkflowResult(
                11L, 5, 502L, stage, "已组树", false, 1));
    when(contexts.loadForCurrentBusinessUnit(11L, "2026-10"))
        .thenReturn(
            new ObjectMapper()
                .convertValue(Map.of("oaNo", "OA-1"), ElectronicDrawingWorkContext.class));
  }

  private void verifyPreparationOnly() {
    verify(pipeline)
        .prepare(
            argThat(
                request ->
                    "OA-1".equals(request.oaNo())
                        && request.oaFormItemId().equals(11L)
                        && "2026-10".equals(request.periodMonth())));
    verify(pipeline, never()).execute(any());
  }

  private ElectronicDrawingMaterialResolutionResponse state(boolean complete) {
    return new ObjectMapper()
        .convertValue(
            Map.of("complete", complete, "accountingMonth", "2026-10"),
            ElectronicDrawingMaterialResolutionResponse.class);
  }
}
