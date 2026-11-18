package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.sanhua.marketingcost.entity.QuoteBomMonthlySnapshot;
import com.sanhua.marketingcost.integration.drawing.DrawingVerificationCodec;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.electronicdrawing.*;
import com.sanhua.marketingcost.service.quotebom.*;
import java.time.*;
import org.junit.jupiter.api.Test;

class TechnicalBomAbsenceQueryTest {
  final LiveU9BomGateway u9=mock(LiveU9BomGateway.class);
  final QuoteBomMonthlySnapshotMapper monthly=mock(QuoteBomMonthlySnapshotMapper.class);
  final ElectronicDrawingWorkflowContextPort contexts=mock(ElectronicDrawingWorkflowContextPort.class);
  final ElectronicDrawingSourceNodeRepository nodes=mock(ElectronicDrawingSourceNodeRepository.class);
  final ElectronicDrawingProductLookup products=mock(ElectronicDrawingProductLookup.class);
  final DrawingVerificationService verification=mock(DrawingVerificationService.class);
  final DrawingVerificationResultMapper results=mock(DrawingVerificationResultMapper.class);
  final TechnicalBomAbsenceQuery query=new TechnicalBomAbsenceQuery(u9,monthly,contexts,nodes,products,verification,
      results,new DrawingVerificationCodec(new com.fasterxml.jackson.databind.ObjectMapper()));
  final QuoteBomReadContext context=new QuoteBomReadContext(1L,2L,"OA","2026-11","COMMERCIAL","NEW","新品","","MODEL",
      "210","COMMERCIAL",LocalDate.of(2026,11,16),LocalDateTime.of(2026,11,16,12,0));

  @Test void existingMonthlyU9WinsWithoutAnyExternalReadOrWrite() {
    var saved=new QuoteBomMonthlySnapshot();saved.setSyncStatus("SUCCESS");saved.setStructureFingerprint("existing");
    when(monthly.selectU9MonthlyByIdentity(anyString())).thenReturn(saved);
    assertThat(query.read(context).state()).isEqualTo("U9");verifyNoInteractions(u9,contexts,nodes,products,verification);
    verify(monthly,never()).insert(any(QuoteBomMonthlySnapshot.class));
  }
  @Test void failedU9NeverBecomesAbsence() {
    when(u9.readLive(context)).thenReturn(CurrentU9BomResult.error("离线"));
    assertThat(query.read(context).allowsEntry()).isFalse();verifyNoInteractions(contexts);
  }
  @Test void noMatchingDrawingAllowsEntryButPendingOrFailedDrawingDoesNot() {
    when(u9.readLive(context)).thenReturn(CurrentU9BomResult.notFound("已核实不存在"));
    when(contexts.load(2L,"COMMERCIAL","210","2026-11")).thenReturn(drawing(null));
    assertThat(query.read(context).allowsEntry()).isTrue();
    for(String state:java.util.List.of(ElectronicDrawingWorkflowStage.QUERYING,ElectronicDrawingWorkflowStage.FAILED,ElectronicDrawingWorkflowStage.MAPPING_PENDING)) {
      when(contexts.load(2L,"COMMERCIAL","210","2026-11")).thenReturn(drawing(state));
      assertThat(query.read(context).state()).isEqualTo("UNCONFIRMED");
    }
    when(contexts.load(2L,"COMMERCIAL","210","2026-11")).thenReturn(drawing(ElectronicDrawingWorkflowStage.NOT_FOUND));
    assertThat(query.read(context).allowsEntry()).isTrue();
  }
  @Test void receivedUnknownGroupWithFailedImportIsNotAbsenceAndNeverTriggersAnotherDrawingRequest() {
    when(u9.readLive(context)).thenReturn(CurrentU9BomResult.notFound("已核实不存在"));
    var scope=drawing(ElectronicDrawingWorkflowStage.VALIDATION_FAILED);
    when(contexts.load(2L,"COMMERCIAL","210","2026-11")).thenReturn(scope);
    var task=new com.sanhua.marketingcost.entity.DrawingVerificationTask();task.setId(49L);
    task.setTaskState("RECEIVED");task.setConsumptionState("FAILED");task.setCurrentResultId(45L);
    when(verification.current(scope)).thenReturn(java.util.Optional.of(task));
    when(verification.matchesCurrentInputs(scope,task)).thenReturn(true);
    var result=new com.sanhua.marketingcost.entity.DrawingVerificationResult();result.setId(45L);result.setVerificationTaskId(49L);
    result.setRawSummaryJson("""
      {"taskId":"received-source","status":"reviewRequired","differenceCount":0,
       "data":{"_unknown_1":{"company":"test","params":{"items":[{"no":"1","code":"RFK-A04-005224","name":"感温部件","qty":"1","mat":"","grade":"B","hsf":"A","weight":null,"note":""}]}}},
       "plm_data":[{"drawingNo":"RFGQ05E-2.5-0326-MX","plmVersion":"V001"}],
       "verificationStatus":"partial","editUrl":null,"reviewReasons":[],"expiresAt":null,"revision":1,"confirmedAt":null,
       "deliveryStatus":null,"deliveryAttempts":0,"engine":null,"drafts":{},"draftEngine":null,"schemaVersion":2}
      """);
    when(results.findById(45L)).thenReturn(result);
    var state=query.read(context);
    assertThat(state.state()).isEqualTo("UNCONFIRMED");assertThat(state.message()).contains("图库已返回资料");
    assertThat(state.allowsEntry()).isFalse();
    verify(verification,never()).start(any(),any());verify(verification,never()).restart(any(),any(),any());
    verifyNoInteractions(products);
  }
  private ElectronicDrawingWorkContext drawing(String stage) {
    return new ElectronicDrawingWorkContext(null,0,1L,null,1L,2L,null,"OA","NEW",null,
        "新品","","MODEL","NON_BARE",null,"2026-11","210","COMMERCIAL","COMMERCIAL",
        "210",true,true,"NEED_TECH",stage,null,null,null);
  }
}
