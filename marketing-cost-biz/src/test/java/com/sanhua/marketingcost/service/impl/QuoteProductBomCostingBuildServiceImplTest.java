package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.FlattenRequest;
import com.sanhua.marketingcost.dto.FlattenResult;
import com.sanhua.marketingcost.dto.quotebom.FormalBomReadResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingBuildResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomSourceLineDto;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomCostingRowSourceRef;
import com.sanhua.marketingcost.entity.BomSupplementTask;
import com.sanhua.marketingcost.entity.QuoteBomPackageReference;
import com.sanhua.marketingcost.entity.QuoteBomPackageReferenceDetail;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.entity.QuoteBomSupplementDetail;
import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.BomCostingRowSourceRefMapper;
import com.sanhua.marketingcost.mapper.BomCostingRowSubRefMapper;
import com.sanhua.marketingcost.mapper.BomSupplementTaskMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPackageReferenceDetailMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPackageReferenceMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.mapper.QuoteBomStatusMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementDetailMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.service.BomByproductCostRuleQueryService;
import com.sanhua.marketingcost.service.BomFlattenService;
import com.sanhua.marketingcost.service.BomSettlementRuleQueryService;
import com.sanhua.marketingcost.service.FormalBomReadService;
import com.sanhua.marketingcost.service.QuoteProductBomPreparationService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import com.sanhua.marketingcost.service.rule.BomByproductCostRuleConditionEvaluator;
import com.sanhua.marketingcost.service.rule.BomByproductCostRuleMatcher;
import com.sanhua.marketingcost.service.rule.BomSettlementRuleConditionEvaluator;
import com.sanhua.marketingcost.service.rule.BomSettlementRuleMatcher;
import com.sanhua.marketingcost.service.settlement.BomByproductSettlementAdapter;
import com.sanhua.marketingcost.service.settlement.BomByproductSettlementReadResult;
import com.sanhua.marketingcost.service.settlement.BomSettlementRowBuildEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QuoteProductBomCostingBuildServiceImplTest {

  private QuoteProductBomPreparationService preparationService;
  private BomFlattenService flattenService;
  private FormalBomReadService formalBomReadService;
  private BomSettlementRuleQueryService settlementRuleQueryService;
  private BomByproductCostRuleQueryService byproductRuleQueryService;
  private BomByproductSettlementAdapter byproductSettlementAdapter;
  private BomSettlementRowBuildEngine buildEngine;
  private QuoteBomPreparationRecordMapper preparationRecordMapper;
  private QuoteBomStatusMapper statusMapper;
  private BomSupplementTaskMapper taskMapper;
  private QuoteBomSupplementVersionMapper supplementVersionMapper;
  private QuoteBomSupplementDetailMapper supplementDetailMapper;
  private QuoteBomPackageReferenceMapper packageReferenceMapper;
  private QuoteBomPackageReferenceDetailMapper packageReferenceDetailMapper;
  private BomCostingRowMapper costingRowMapper;
  private BomCostingRowSourceRefMapper sourceRefMapper;
  private BomCostingRowSubRefMapper subRefMapper;
  private OaFormItemMapper oaFormItemMapper;
  private QuoteProductBomCostingBuildServiceImpl service;

  @BeforeEach
  void setUp() {
    preparationService = mock(QuoteProductBomPreparationService.class);
    flattenService = mock(BomFlattenService.class);
    formalBomReadService = mock(FormalBomReadService.class);
    settlementRuleQueryService = mock(BomSettlementRuleQueryService.class);
    byproductRuleQueryService = mock(BomByproductCostRuleQueryService.class);
    byproductSettlementAdapter = mock(BomByproductSettlementAdapter.class);
    ObjectMapper objectMapper = new ObjectMapper();
    buildEngine =
        new BomSettlementRowBuildEngine(
            new BomSettlementRuleMatcher(new BomSettlementRuleConditionEvaluator(objectMapper)),
            new BomByproductCostRuleMatcher(
                new BomByproductCostRuleConditionEvaluator(objectMapper)));
    preparationRecordMapper = mock(QuoteBomPreparationRecordMapper.class);
    statusMapper = mock(QuoteBomStatusMapper.class);
    taskMapper = mock(BomSupplementTaskMapper.class);
    supplementVersionMapper = mock(QuoteBomSupplementVersionMapper.class);
    supplementDetailMapper = mock(QuoteBomSupplementDetailMapper.class);
    packageReferenceMapper = mock(QuoteBomPackageReferenceMapper.class);
    packageReferenceDetailMapper = mock(QuoteBomPackageReferenceDetailMapper.class);
    costingRowMapper = mock(BomCostingRowMapper.class);
    sourceRefMapper = mock(BomCostingRowSourceRefMapper.class);
    subRefMapper = mock(BomCostingRowSubRefMapper.class);
    oaFormItemMapper = mock(OaFormItemMapper.class);
    service =
        new QuoteProductBomCostingBuildServiceImpl(
            preparationService,
            flattenService,
            formalBomReadService,
            settlementRuleQueryService,
            byproductRuleQueryService,
            byproductSettlementAdapter,
            buildEngine,
            preparationRecordMapper,
            statusMapper,
            taskMapper,
            supplementVersionMapper,
            supplementDetailMapper,
            packageReferenceMapper,
            packageReferenceDetailMapper,
            costingRowMapper,
            sourceRefMapper,
            subRefMapper,
            oaFormItemMapper);
    when(settlementRuleQueryService.listEnabledCandidates()).thenReturn(List.of());
    when(byproductRuleQueryService.listEnabledCandidates()).thenReturn(List.of());
    when(byproductSettlementAdapter.read(any(), any(), any(), any()))
        .thenReturn(new BomByproductSettlementReadResult(List.of(), List.of(), List.of()));
    doAnswer(invocation -> {
      BomCostingRow row = invocation.getArgument(0, BomCostingRow.class);
      row.setId(9000L + row.getMaterialCode().hashCode() % 1000);
      return 1;
    }).when(costingRowMapper).insert(any(BomCostingRow.class));
    when(sourceRefMapper.insert(any(BomCostingRowSourceRef.class))).thenReturn(1);
  }

  @Test
  void nonBareFormalBuildReusesExistingFlattenAndWritesRawSourceRefs() {
    QuoteBomPreparationRecord record = record("NON_BARE", null);
    when(preparationRecordMapper.selectOne(any())).thenReturn(record);
    FlattenResult flattenResult = new FlattenResult();
    flattenResult.setCostingRowsWritten(2);
    when(flattenService.flatten(any(FlattenRequest.class))).thenReturn(flattenResult);
    when(costingRowMapper.selectList(any())).thenReturn(List.of(costingRow("RAW-1"), costingRow("RAW-2")));

    QuoteBomCostingBuildResponse response = service.buildByOaFormItem(10L);

    assertThat(response.costingRowsWritten()).isEqualTo(2);
    assertThat(response.sourceTypeCounts()).containsEntry("RAW_PRODUCT_BOM", 2);
    ArgumentCaptor<FlattenRequest> flattenCaptor = ArgumentCaptor.forClass(FlattenRequest.class);
    verify(flattenService).flatten(flattenCaptor.capture());
    assertThat(flattenCaptor.getValue().getAsOfDate()).isEqualTo(LocalDate.now());
    assertThat(flattenCaptor.getValue().getPeriodMonth()).isEqualTo("2026-05");
    ArgumentCaptor<BomCostingRowSourceRef> refCaptor = ArgumentCaptor.forClass(BomCostingRowSourceRef.class);
    verify(sourceRefMapper, org.mockito.Mockito.times(2)).insert(refCaptor.capture());
    assertThat(refCaptor.getAllValues()).allSatisfy(ref -> assertThat(ref.getSourcePartType()).isEqualTo("RAW_PRODUCT_BOM"));
    ArgumentCaptor<BomCostingRow> rowPatchCaptor = ArgumentCaptor.forClass(BomCostingRow.class);
    verify(costingRowMapper, org.mockito.Mockito.times(2)).updateById(rowPatchCaptor.capture());
    assertThat(rowPatchCaptor.getAllValues())
        .allSatisfy(row -> {
          assertThat(row.getOaFormItemId()).isEqualTo(10L);
          assertThat(row.getManualModified()).isEqualTo(0);
        });
  }

  @Test
  void staleNonReadyPreparationIsRefreshedBeforeBuild() {
    QuoteBomPreparationRecord stale = record("NON_BARE", null);
    stale.setPreparationStatus("NEED_TECH");
    QuoteBomPreparationRecord refreshed = record("NON_BARE", null);
    when(preparationRecordMapper.selectOne(any())).thenReturn(stale, refreshed);
    FlattenResult flattenResult = new FlattenResult();
    flattenResult.setCostingRowsWritten(1);
    when(flattenService.flatten(any(FlattenRequest.class))).thenReturn(flattenResult);
    when(costingRowMapper.selectList(any())).thenReturn(List.of(costingRow("RAW-1")));

    QuoteBomCostingBuildResponse response = service.buildByOaFormItem(10L);

    assertThat(response.costingRowsWritten()).isEqualTo(1);
    verify(preparationService).prepareByOaFormItem(10L, LocalDate.now());
    verify(flattenService).flatten(any(FlattenRequest.class));
  }

  @Test
  void nonBareApprovedSupplementBuildsManualSourceRowsWithoutFormalFlatten() {
    QuoteBomPreparationRecord record = record("NON_BARE", 501L);
    record.setReviewStatus("APPROVED");
    when(taskMapper.selectById(501L)).thenReturn(task("APPROVED"));
    when(preparationRecordMapper.selectOne(any())).thenReturn(record);
    when(supplementVersionMapper.selectOne(any())).thenReturn(version("NON_BARE_FULL_BOM"));
    when(packageReferenceMapper.selectOne(any())).thenReturn(null);
    when(supplementDetailMapper.selectList(any())).thenReturn(List.of(supplementDetail("MAT-1")));

    QuoteBomCostingBuildResponse response = service.buildByTask(501L);

    assertThat(response.costingRowsWritten()).isEqualTo(1);
    assertThat(response.sourceTypeCounts()).containsEntry("MANUAL_SUPPLEMENT", 1);
    verify(flattenService, never()).flatten(any());
    verify(settlementRuleQueryService).listEnabledCandidates();
    ArgumentCaptor<BomCostingRow> rowCaptor = ArgumentCaptor.forClass(BomCostingRow.class);
    verify(costingRowMapper).insert(rowCaptor.capture());
    assertThat(rowCaptor.getValue().getOaFormItemId()).isEqualTo(10L);
    assertThat(rowCaptor.getValue().getManualModified()).isEqualTo(0);
  }

  @Test
  void bareFormalBodyAndApprovedPackageBuildsCombinedSources() {
    QuoteBomPreparationRecord record = record("BARE", 501L);
    record.setReviewStatus("APPROVED");
    when(taskMapper.selectById(501L)).thenReturn(task("APPROVED"));
    when(preparationRecordMapper.selectOne(any())).thenReturn(record);
    when(supplementVersionMapper.selectOne(any())).thenReturn(null);
    when(packageReferenceMapper.selectOne(any())).thenReturn(packageReference());
    when(formalBomReadService.read("FIN-001", "2026-05", null, LocalDate.now()))
        .thenReturn(formalFound());
    when(packageReferenceDetailMapper.selectList(any())).thenReturn(List.of(packageDetail("BOX-1")));

    QuoteBomCostingBuildResponse response = service.buildByTask(501L);

    assertThat(response.costingRowsWritten()).isEqualTo(2);
    assertThat(response.sourceTypeCounts()).containsEntry("BARE_PRODUCT_BOM", 1);
    assertThat(response.sourceTypeCounts()).containsEntry("REFERENCED_PACKAGE", 1);
  }

  @Test
  void bareSupplementBodyAndApprovedPackageBuildsManualAndPackageSources() {
    QuoteBomPreparationRecord record = record("BARE", 501L);
    record.setReviewStatus("APPROVED");
    when(taskMapper.selectById(501L)).thenReturn(task("APPROVED"));
    when(preparationRecordMapper.selectOne(any())).thenReturn(record);
    when(supplementVersionMapper.selectOne(any())).thenReturn(version("BARE_BODY_BOM"));
    when(supplementDetailMapper.selectList(any())).thenReturn(List.of(supplementDetail("BODY-1")));
    when(packageReferenceMapper.selectOne(any())).thenReturn(packageReference());
    when(packageReferenceDetailMapper.selectList(any())).thenReturn(List.of(packageDetail("BOX-1")));

    QuoteBomCostingBuildResponse response = service.buildByTask(501L);

    assertThat(response.costingRowsWritten()).isEqualTo(2);
    assertThat(response.sourceTypeCounts()).containsEntry("MANUAL_SUPPLEMENT", 1);
    assertThat(response.sourceTypeCounts()).containsEntry("REFERENCED_PACKAGE", 1);
  }

  @Test
  void unapprovedTaskCannotBuildCostingRows() {
    QuoteBomPreparationRecord record = record("NON_BARE", 501L);
    record.setReviewStatus("PENDING");
    when(taskMapper.selectById(501L)).thenReturn(task("FINANCE_REVIEW"));
    when(preparationRecordMapper.selectOne(any())).thenReturn(record);

    assertThatThrownBy(() -> service.buildByTask(501L))
        .isInstanceOf(QuoteIngestException.class)
        .hasMessageContaining("审核通过");
    verify(costingRowMapper, never()).insert(any(BomCostingRow.class));
  }

  private QuoteBomPreparationRecord record(String productType, Long taskId) {
    QuoteBomPreparationRecord record = new QuoteBomPreparationRecord();
    record.setId(201L);
    record.setQuoteBomStatusId(101L);
    record.setOaFormId(20L);
    record.setOaFormItemId(10L);
    record.setOaNo("OA-001");
    record.setQuoteProductCode("FIN-001");
    record.setProductType(productType);
    record.setNeedPackage("BARE".equals(productType) ? 1 : 0);
    record.setReferenceFinishedCode("BARE".equals(productType) ? "REF-001" : null);
    record.setSourceTopProductCode("BARE".equals(productType) ? "REF-001" : null);
    record.setCostPeriodMonth("2026-05");
    record.setPreparationStatus("READY");
    record.setReviewStatus("NOT_SUBMITTED");
    record.setTaskId(taskId);
    record.setActiveFlag(1);
    return record;
  }

  private BomSupplementTask task(String status) {
    BomSupplementTask task = new BomSupplementTask();
    task.setId(501L);
    task.setTaskStatus(status);
    return task;
  }

  private QuoteBomSupplementVersion version(String scope) {
    QuoteBomSupplementVersion version = new QuoteBomSupplementVersion();
    version.setId(601L);
    version.setTaskId(501L);
    version.setSupplementScope(scope);
    version.setVersionStatus("APPROVED");
    version.setActiveFlag(1);
    return version;
  }

  private QuoteBomPackageReference packageReference() {
    QuoteBomPackageReference reference = new QuoteBomPackageReference();
    reference.setId(801L);
    reference.setTaskId(501L);
    reference.setReferenceFinishedCode("REF-001");
    reference.setSourceTopProductCode("REF-001");
    reference.setReferenceStatus("APPROVED");
    reference.setActiveFlag(1);
    return reference;
  }

  private QuoteBomSupplementDetail supplementDetail(String code) {
    QuoteBomSupplementDetail detail = new QuoteBomSupplementDetail();
    detail.setId(701L);
    detail.setSupplementVersionId(601L);
    detail.setTaskId(501L);
    detail.setLineNo(1);
    detail.setLevel(1);
    detail.setParentCode("FIN-001");
    detail.setMaterialCode(code);
    detail.setMaterialName(code + " name");
    detail.setQtyPerParent(BigDecimal.ONE);
    detail.setQtyPerTop(BigDecimal.ONE);
    detail.setPath("/FIN-001/" + code + "/");
    return detail;
  }

  private QuoteBomPackageReferenceDetail packageDetail(String code) {
    QuoteBomPackageReferenceDetail detail = new QuoteBomPackageReferenceDetail();
    detail.setId(901L);
    detail.setPackageReferenceId(801L);
    detail.setTaskId(501L);
    detail.setLineNo(1);
    detail.setPackageParentCode("PKG-PARENT");
    detail.setPackageMaterialCode(code);
    detail.setPackageMaterialName(code + " name");
    detail.setChildQtyPerParent(BigDecimal.ONE);
    detail.setChildQtyPerTop(BigDecimal.ONE);
    detail.setQtyPerTop(BigDecimal.ONE);
    detail.setSelectedFlag(1);
    return detail;
  }

  private FormalBomReadResult formalFound() {
    return new FormalBomReadResult(
        "FIN-001",
        "2026-05",
        null,
        true,
        List.of(
            new QuoteBomSourceLineDto(
                301L,
                1,
                1,
                "FIN-001",
                "FIN-001",
                "BODY-RAW",
                "本体材料",
                "规格",
                null,
                null,
                "实体",
                "1201",
                "PCS",
                "采购件",
                "CE",
                null,
                null,
                BigDecimal.ONE,
                BigDecimal.ONE,
                null,
                "/FIN-001/BODY-RAW/",
                1,
                301L,
                null,
                0)),
        null);
  }

  private BomCostingRow costingRow(String code) {
    BomCostingRow row = new BomCostingRow();
    row.setId((long) code.hashCode());
    row.setOaNo("OA-001");
    row.setTopProductCode("FIN-001");
    row.setMaterialCode(code);
    row.setRawHierarchyNodeId(301L);
    row.setPath("/FIN-001/" + code + "/");
    row.setBuildBatchId("f_20260528_abcdef");
    row.setPeriodMonth("2026-05");
    row.setBuiltAt(LocalDateTime.now());
    row.setAsOfDate(LocalDate.of(2026, 5, 31));
    return row;
  }
}
