package com.sanhua.marketingcost.service.electronicdrawing;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.electronicdrawing.ElectronicDrawingBomCorrectionRequest;
import com.sanhua.marketingcost.dto.electronicdrawing.ElectronicDrawingMaterialResolutionResponse;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.mapper.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ElectronicDrawingBomCorrectionServiceTest {
  @Test
  void nonEditableSourceCannotCreateRevisionOrMutateNodesOrPrices() {
    var contexts = mock(ElectronicDrawingWorkflowContextPort.class);
    var preparations = mock(QuoteBomPreparationRecordMapper.class);
    var versions = mock(QuoteBomSupplementVersionMapper.class);
    var nodes = mock(ElectronicDrawingSourceNodeRepository.class);
    var materials = mock(MaterialMasterRawMapper.class);
    var actors = mock(ElectronicDrawingActorProvider.class);
    var resolutions = mock(ElectronicDrawingMaterialResolutionService.class);
    var changes = mock(BusinessChangeLogMapper.class);
    var service =
        new ElectronicDrawingBomCorrectionService(
            contexts,
            preparations,
            versions,
            nodes,
            materials,
            actors,
            resolutions,
            new ObjectMapper(),
            changes);
    var context =
        new ObjectMapper()
            .convertValue(
                Map.of(
                    "preparationId",
                    30L,
                    "revision",
                    4,
                    "sourceVersionId",
                    100L,
                    "active",
                    true,
                    "bomRequired",
                    true,
                    "workflowStatus",
                    "READY_FOR_COSTING",
                    "compositionFingerprint",
                    "published"),
                ElectronicDrawingWorkContext.class);
    when(actors.current()).thenReturn(new ElectronicDrawingActor(1L, "财务"));
    when(contexts.loadForCurrentBusinessUnit(10L, "2026-10")).thenReturn(context);
    var preparation = new QuoteBomPreparationRecord();
    preparation.setElectronicWorkflowVersion(4);
    preparation.setElectronicSourceVersionId(100L);
    when(preparations.selectForElectronicDrawingImport(30L)).thenReturn(preparation);
    var item =
        new ObjectMapper()
            .convertValue(
                Map.of("sourceNodeId", 200L, "canModify", false),
                ElectronicDrawingMaterialResolutionResponse.Item.class);
    var state =
        new ObjectMapper()
            .convertValue(
                Map.of(
                    "items",
                    List.of(item),
                    "sourceVersionStatus",
                    "APPROVED",
                    "bomPublished",
                    true),
                ElectronicDrawingMaterialResolutionResponse.class);
    when(resolutions.state(10L, "2026-10")).thenReturn(state);
    var request =
        new ElectronicDrawingBomCorrectionRequest(
            4,
            100L,
            200L,
            "核实后拆分",
            List.of(
                new ElectronicDrawingBomCorrectionRequest.Line("301300203", BigDecimal.ONE, "千克")));
    assertThatThrownBy(() -> service.save(10L, request, "2026-10"))
        .isInstanceOf(ElectronicDrawingMaterialResolutionException.class)
        .hasMessageContaining("不可编辑");
    verifyNoInteractions(versions, nodes, materials, changes);
  }
}
