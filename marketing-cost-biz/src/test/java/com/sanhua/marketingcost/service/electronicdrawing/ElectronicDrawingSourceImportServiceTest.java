package com.sanhua.marketingcost.service.electronicdrawing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.integration.drawing.DrawingVerificationCodec;
import com.sanhua.marketingcost.integration.drawing.DrawingVerificationFixtures;
import com.sanhua.marketingcost.mapper.*;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class ElectronicDrawingSourceImportServiceTest {
  final ElectronicDrawingWorkflowContextPort contexts =
      mock(ElectronicDrawingWorkflowContextPort.class);
  final OaFormItemMapper items = mock(OaFormItemMapper.class);
  final QuoteBomPreparationRecordMapper preparations = mock(QuoteBomPreparationRecordMapper.class);
  final QuoteBomSupplementVersionMapper versions = mock(QuoteBomSupplementVersionMapper.class);
  final ElectronicDrawingSourceNodeRepository nodes =
      mock(ElectronicDrawingSourceNodeRepository.class);
  final DrawingVerificationTaskMapper tasks = mock(DrawingVerificationTaskMapper.class);
  final DrawingVerificationResultMapper results = mock(DrawingVerificationResultMapper.class);
  final DrawingVerificationService verifications = mock(DrawingVerificationService.class);
  final DrawingVerificationCodec codec = new DrawingVerificationCodec(new ObjectMapper());
  final ElectronicDrawingSourceImportService service =
      new ElectronicDrawingSourceImportService(
          contexts, items, preparations, versions, nodes, tasks, results, codec, verifications);
  DrawingVerificationTask task;
  DrawingVerificationResult result;
  ElectronicDrawingWorkContext scope;

  @BeforeEach
  void setup() {
    scope =
        new ElectronicDrawingWorkContext(
            101L,
            0,
            301L,
            null,
            10L,
            101L,
            "TASK",
            "OA",
            "P",
            null,
            "Product",
            null,
            null,
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
    task = new DrawingVerificationTask();
    task.setId(501L);
    task.setOaFormItemId(101L);
    task.setOaNo("OA");
    task.setBusinessUnitType("COMMERCIAL");
    task.setMaterialOrgCode("COMMERCIAL");
    task.setPriceOrgCode("210");
    task.setAccountingMonth("2026-08");
    task.setDrawingNo("DRAW-01");
    task.setTaskState("RECEIVED");
    task.setActiveScopeKey("active");
    task.setCurrentResultId(601L);
    task.setRequestId("request");
    result = new DrawingVerificationResult();
    result.setId(601L);
    result.setVerificationTaskId(501L);
    result.setEventType("INITIAL");
    result.setReceivedAt(LocalDateTime.of(2026, 8, 1, 1, 1));
    result.setRawSummaryJson(DrawingVerificationFixtures.initial("external").toString());
    var preparation = new QuoteBomPreparationRecord();
    preparation.setId(301L);
    preparation.setOaFormItemId(101L);
    preparation.setOaFormId(10L);
    preparation.setQuoteProductCode("P");
    preparation.setCostPeriodMonth("2026-08");
    preparation.setMaterialOrganizationCode("COMMERCIAL");
    preparation.setActiveFlag(1);
    when(contexts.load(101L, "COMMERCIAL", "210", "2026-08")).thenReturn(scope);
    when(results.findById(601L)).thenReturn(result);
    when(tasks.selectById(501L)).thenReturn(task);
    when(tasks.selectForUpdate(501L)).thenReturn(task);
    when(preparations.selectForElectronicDrawingImport(301L)).thenReturn(preparation);
    when(verifications.inputsStillMatch(scope, task)).thenReturn(true);
    when(versions.selectList(any())).thenReturn(List.of());
    when(versions.insert(any(QuoteBomSupplementVersion.class)))
        .thenAnswer(
            call -> {
              ((QuoteBomSupplementVersion) call.getArgument(0)).setId(701L);
              return 1;
            });
    when(tasks.completeConsumption(eq(501L), eq(601L), any())).thenReturn(1);
  }

  @Test
  void importsOnlyVerifiedGroupWithDistinctRowsAndRealJsonEvidence() {
    var imported = service.importSource(scope, 601L);
    var capture = ArgumentCaptor.forClass(QuoteBomSupplementVersion.class);
    verify(versions).insert(capture.capture());
    var source = capture.getValue();
    assertThat(source.getBomSource()).isEqualTo(ElectronicDrawingSourceKind.PDF);
    assertThat(source.getDrawingResultId()).isEqualTo(601L);
    assertThat(source.getSourceFileName()).isNull();
    assertThat(source.getSourceFileSha256()).isNull();
    assertThat(source.getSourceFileSize()).isNull();
    assertThat(source.getSourceSheetName()).isNull();
    assertThat(imported.sourceNodeCount()).isEqualTo(2);
    verify(nodes)
        .insertAll(
            eq(701L),
            argThat(
                rows ->
                    rows.size() == 2
                        && rows.get(0).getSourceSequence().equals("G1:R1")
                        && rows.get(1).getSourceSequence().equals("G1:R2")
                        && rows.stream()
                            .allMatch(
                                row ->
                                    row.getParentSourceSequence() == null
                                        && row.getReferenceWeightUnit() == null)));
    assertThat(result.getRawSummaryJson()).contains("DRAW-02", "PART-002");
    verify(tasks).completeConsumption(eq(501L), eq(601L), any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"WAIT_REVIEW", "SUPERSEDED", "REQUESTING", "FAILED"})
  void doesNotImportUnadoptedTask(String state) {
    task.setTaskState(state);
    assertThatThrownBy(() -> service.importSource(scope, 601L)).hasMessageContaining("不一致");
    verifyNoInteractions(nodes);
    verify(versions, never()).insert(any(QuoteBomSupplementVersion.class));
  }

  @Test
  void changedQuoteInputsAreNotImported() {
    when(verifications.inputsStillMatch(scope, task)).thenReturn(false);
    assertThatThrownBy(() -> service.importSource(scope, 601L)).hasMessageContaining("输入不一致");
    verifyNoInteractions(nodes);
  }

  @Test
  void neverImportsResultFromAnotherQuote() {
    task.setOaFormItemId(999L);
    assertThatThrownBy(() -> service.importSource(scope, 601L)).hasMessageContaining("对应的报价产品");
    verifyNoInteractions(nodes);
  }

  @Test
  void failedPointerUpdateThrowsSoTransactionCanRollback() {
    when(tasks.completeConsumption(eq(501L), eq(601L), any())).thenReturn(0);
    assertThatThrownBy(() -> service.importSource(scope, 601L))
        .isInstanceOf(ElectronicDrawingWorkflowRetryException.class);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "0", "-1", "two", "0.000000001", "1000000000000"})
  void invalidCalculationQuantityNeverCreatesSource(String quantity) {
    var body = DrawingVerificationFixtures.initial("external");
    ((com.fasterxml.jackson.databind.node.ObjectNode)
            body.path("data").path("DRAW-01").path("params").path("items").get(0))
        .put("qty", quantity);
    result.setRawSummaryJson(body.toString());
    assertThatThrownBy(() -> service.importSource(scope, 601L))
        .isInstanceOf(ElectronicDrawingSourceImportException.class)
        .hasMessageContaining("数量");
    verifyNoInteractions(nodes);
    verify(versions, never()).insert(any(QuoteBomSupplementVersion.class));
  }

  @Test
  void repeatedResultReusesExistingSourceWithoutMutatingHistoricalRows() {
    var existing = new QuoteBomSupplementVersion();
    existing.setId(700L);
    existing.setVersionNo(1);
    existing.setVersionStatus("APPROVED");
    existing.setDrawingResultId(601L);
    when(versions.selectList(any())).thenReturn(List.of(existing));
    when(nodes.findByVersionId(700L)).thenReturn(List.of(new ElectronicDrawingSourceNode()));
    assertThat(service.importSource(scope, 601L).supplementVersionId()).isEqualTo(700L);
    verify(versions, never()).insert(any(QuoteBomSupplementVersion.class));
    verify(versions, never()).updateById(any(QuoteBomSupplementVersion.class));
    verify(nodes, never()).insertAll(any(), any());
  }
}
