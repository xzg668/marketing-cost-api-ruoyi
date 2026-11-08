package com.sanhua.marketingcost.service.electronicdrawing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.quotebom.ApprovedElectronicBomRawSnapshotPublisher;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ElectronicDrawingPreparationSourceTest {
  final ElectronicDrawingWorkflowContextPort contexts =
      mock(ElectronicDrawingWorkflowContextPort.class);
  final QuoteBomSupplementVersionMapper versions = mock(QuoteBomSupplementVersionMapper.class);
  final QuoteBomMonthlySnapshotMapper snapshots = mock(QuoteBomMonthlySnapshotMapper.class);
  final ApprovedElectronicBomRawSnapshotPublisher converter =
      mock(ApprovedElectronicBomRawSnapshotPublisher.class);
  final com.sanhua.marketingcost.service.technicaldata.TechnicalDataCostingSources
      technicalSources =
          mock(com.sanhua.marketingcost.service.technicaldata.TechnicalDataCostingSources.class);
  final com.sanhua.marketingcost.service.technicaldata.TechnicalDataVersionContentCodec content =
      mock(com.sanhua.marketingcost.service.technicaldata.TechnicalDataVersionContentCodec.class);
  final ElectronicDrawingSourceNodeRepository sourceNodes =
      mock(ElectronicDrawingSourceNodeRepository.class);
  final com.sanhua.marketingcost.service.QuoteProductBomPreparationService preparation =
      mock(com.sanhua.marketingcost.service.QuoteProductBomPreparationService.class);
  final QuoteBomPreparationRecordMapper preparations = mock(QuoteBomPreparationRecordMapper.class);
  final DrawingVerificationService verifications = mock(DrawingVerificationService.class);
  final ElectronicDrawingPreparationSource service =
      new ElectronicDrawingPreparationSource(
          contexts,
          versions,
          snapshots,
          converter,
          technicalSources,
          content,
          new com.sanhua.marketingcost.integration.oa.OaMessageCodec(
              new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()),
          sourceNodes,
          verifications);
  QuoteBomSupplementVersion version;
  final QuoteTechTask sourceTask = new QuoteTechTask();

  @Test
  void readingAnExistingMonthlySnapshotDoesNotRequireStartingANewVerification() {
    when(verifications.current(any())).thenReturn(java.util.Optional.empty());
    assertThat(service.currentBlockReason(99L, "2026-10", "COMMERCIAL", "210")).isNull();
    verifyNoInteractions(converter, versions);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"WAIT_REVIEW", "FAILED", "REQUESTING"})
  void anOldApprovedBomCannotReplaceAnUnfinishedLatestVerification(String state) {
    version.setVersionStatus("APPROVED");
    var task = new DrawingVerificationTask(); task.setTaskState(state);
    when(verifications.current(any())).thenReturn(java.util.Optional.of(task));
    assertThat(service.currentBlockReason(2L, "2026-09", "COMMERCIAL", "210")).isNotBlank();
    verifyNoInteractions(converter);
  }

  @Test
  void aNewResultMustBeConsumedIntoTheCurrentSourceBeforeReadingTheBom() {
    var task = new DrawingVerificationTask(); task.setTaskState("RECEIVED");
    task.setCurrentResultId(30L); task.setConsumedResultId(20L); version.setDrawingResultId(20L);
    when(verifications.current(any())).thenReturn(java.util.Optional.of(task));
    assertThat(service.currentBlockReason(2L, "2026-09", "COMMERCIAL", "210")).contains("等待处理");
    task.setConsumedResultId(30L); version.setDrawingResultId(30L);
    assertThat(service.currentBlockReason(2L, "2026-09", "COMMERCIAL", "210")).isNull();
    verifyNoInteractions(converter);
  }

  @BeforeEach
  void setup() {
    sourceTask.setBusinessUnitType("COMMERCIAL");
    sourceTask.setApplicableOrgCode("210");
    when(contexts.load(99L, "COMMERCIAL", "210", "2026-10")).thenReturn(
        new ElectronicDrawingWorkContext(99L, 0, 100L, null, 98L, 99L, "T", "NEW-OA", "P", null,
            "产品", null, null, null, "FULL_BOM", "2026-10", "210", "COMMERCIAL", "COMMERCIAL", "210",
            true, true, "BOM_IN_PROGRESS", null, null, null, null));
    com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
        new org.apache.ibatis.builder.MapperBuilderAssistant(
            new com.baomidou.mybatisplus.core.MybatisConfiguration(), "drawing-preparation-test"),
        QuoteBomPreparationRecord.class);
    when(contexts.load(2L, "COMMERCIAL", "210", "2026-09"))
        .thenReturn(
            new ElectronicDrawingWorkContext(
                2L,
                1,
                4L,
                5L,
                1L,
                2L,
                "T",
                "OA",
                "P",
                null,
                "产品",
                null,
                null,
                null,
                "FULL_BOM",
                "2026-09",
                "210",
                "COMMERCIAL",
                "COMMERCIAL",
                "210",
                true,
                true,
                "BOM_IN_PROGRESS",
                ElectronicDrawingWorkflowStage.COMPOSED,
                9L,
                "技术员",
                null));
    version = new QuoteBomSupplementVersion();
    version.setId(5L);
    version.setPreparationId(4L);
    version.setPeriodMonth("2026-09");
    version.setMaterialOrgCode("COMMERCIAL");
    version.setQuoteProductCode("P");
    version.setVersionStatus("DRAFT");
    version.setActiveFlag(1);
    version.setCompositionFingerprint("fingerprint-1");
    when(versions.selectById(5L)).thenReturn(version);
    when(snapshots.selectList(any())).thenReturn(List.of());
    when(converter.preview(any(), any())).thenReturn(List.of(new BomRawHierarchy()));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"DRAFT", "APPROVED"})
  void historicalSharedSnapshotStillRejectsChangedOriginalNodes(String sourceStatus) {
    version.setVersionStatus(sourceStatus);
    var product = new QuoteTechProduct();
    product.setId(10L);
    product.setOaFormItemId(2L);
    product.setAccountingMonth("2026-09");
    var approved = new QuoteTechDataVersion();
    approved.setId(80L);
    var shared =
        new com.sanhua.marketingcost.service.technicaldata.TechnicalDataCostingSources.Source(
            "DRAWING_BOM", sourceTask, product, approved, 79L);
    when(technicalSources.sharedDrawing(99L, "2026-10")).thenReturn(shared);
    var row = new ElectronicDrawingSourceNode();
    row.setId(8L);
    row.setSourceSequence("1");
    row.setQty(new java.math.BigDecimal("2.0"));
    row.setResolvedMaterialCode("PART");
    row.setMatchStatus(ElectronicDrawingSourceNode.MATCH_AUTO);
    when(sourceNodes.findByVersionId(5L)).thenReturn(List.of(row));
    var frozen =
        com.sanhua.marketingcost.service.technicaldata.TechnicalDataDrawingSnapshot.from(
            contexts.load(2L, "COMMERCIAL", "210", "2026-09"), version, List.of(row));
    when(content.drawingBom(approved)).thenReturn(frozen);
    var snapshot = historicalSnapshot();
    assertThat(service.rows(snapshot)).hasSize(1);
    verify(converter, never()).publish(any());
    row.setQty(new java.math.BigDecimal("3.0"));
    assertThatThrownBy(() -> service.rows(snapshot)).hasMessageContaining("批准快照不一致");
  }

  @Test
  void changedLibraryVersionRequiresSourceRecheckWithoutReturningOriginalTask() {
    var product = new QuoteTechProduct();
    product.setOaFormItemId(2L);
    product.setAccountingMonth("2026-09");
    var approved = new QuoteTechDataVersion();
    var shared =
        new com.sanhua.marketingcost.service.technicaldata.TechnicalDataCostingSources.Source(
            "DRAWING_BOM", sourceTask, product, approved, 79L);
    when(technicalSources.sharedDrawing(99L, "2026-10")).thenReturn(shared);
    var previous = new QuoteBomSupplementVersion();
    previous.setId(3L);
    var row = new ElectronicDrawingSourceNode();
    row.setId(8L);
    row.setSourceSequence("1");
    row.setMatchStatus(ElectronicDrawingSourceNode.MATCH_AUTO);
    row.setResolvedMaterialCode("PART");
    var frozen =
        com.sanhua.marketingcost.service.technicaldata.TechnicalDataDrawingSnapshot.from(
            contexts.load(2L, "COMMERCIAL", "210", "2026-09"), previous, List.of(row));
    when(content.drawingBom(approved)).thenReturn(frozen);
    assertThatThrownBy(
            () -> service.rows(historicalSnapshot()))
        .hasMessageContaining("来源版本已更新")
        .hasMessageNotContaining("退回");
    verify(snapshots, never()).insert(any(QuoteBomMonthlySnapshot.class));
    verifyNoInteractions(preparation, preparations);
  }

  private QuoteBomMonthlySnapshot historicalSnapshot() {
    var row = new QuoteBomMonthlySnapshot();
    row.setSourceOaFormItemId(99L); row.setCostPeriodMonth("2026-10"); row.setBusinessUnitType("COMMERCIAL");
    row.setPriceOrgCode("210"); row.setProductCode("P"); row.setBomBatchId("ED_SHARED:80:5:fingerprint-1");
    row.setStructureFingerprint("fingerprint-1"); return row;
  }

  @Test
  void ownPreparationCannotTreatPublishedVersionAsDraft() {
    version.setVersionStatus("APPROVED");
    assertThatThrownBy(
            () -> service.snapshot(2L, "2026-09", "COMMERCIAL", "210", "CUSTOMER", "BOX"))
        .hasMessageContaining("可用状态不一致");
    verify(snapshots, never()).insert(any(QuoteBomMonthlySnapshot.class));
  }

  @Test
  void createsOnlyQuotationScopedDraftAndKeepsSourceUnapproved() {
    var result = service.snapshot(2L, "2026-09", "COMMERCIAL", "210", "CUSTOMER", "BOX");
    assertThat(result.getSyncStatus()).isEqualTo("DRAFT");
    assertThat(result.getSourceOaFormItemId()).isEqualTo(2L);
    assertThat(result.getStructureFingerprint()).isEqualTo("fingerprint-1");
    assertThat(result.getBomBatchId()).isEqualTo("ED_DRAFT:5:fingerprint-1");
    assertThat(service.rows(result)).hasSize(1);
    assertThat(version.getVersionStatus()).isEqualTo("DRAFT");
    verify(converter, never()).publish(any());
  }

  @Test
  void fullFingerprintIsCheckedEvenWhenBatchPrefixMatches() {
    String prefix = "a".repeat(32);
    version.setCompositionFingerprint(prefix + "b".repeat(32));
    var result = service.snapshot(2L, "2026-09", "COMMERCIAL", "210", "CUSTOMER", "BOX");
    assertThat(result.getBomBatchId()).hasSizeLessThanOrEqualTo(64);
    version.setCompositionFingerprint(prefix + "c".repeat(32));
    assertThatThrownBy(() -> service.rows(result)).hasMessageContaining("草稿已变化");
  }

  @Test
  void rejectsChangedCompositionInsteadOfReusingPreviousDraft() {
    var result = service.snapshot(2L, "2026-09", "COMMERCIAL", "210", "CUSTOMER", "BOX");
    version.setCompositionFingerprint("fingerprint-2");
    assertThatThrownBy(() -> service.rows(result)).hasMessageContaining("草稿已变化");
  }

  @Test
  void rejectsVersionFromOtherProductMonthOrOrganization() {
    version.setPeriodMonth("2026-10");
    assertThatThrownBy(
            () -> service.snapshot(2L, "2026-09", "COMMERCIAL", "210", "CUSTOMER", "BOX"))
        .hasMessageContaining("不一致");
    verify(snapshots, never()).insert(any(QuoteBomMonthlySnapshot.class));
  }
}
