package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingBuildResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteProductBomPreparationPreview;
import com.sanhua.marketingcost.dto.ingest.QuoteBomStatusItemResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunSummaryResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostingWorkbenchBomRowResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostingWorkbenchResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareSummaryResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeRecognitionSummaryResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeRecognitionResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeRecognitionSummary;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomCostingRowSubRef;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import com.sanhua.marketingcost.entity.QuoteCostingWorkspace;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.BomCostingRowSubRefMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomStatusMapper;
import com.sanhua.marketingcost.mapper.QuoteCostingWorkbenchSummaryMapper;
import com.sanhua.marketingcost.service.QuotePriceTypeRecognitionService;
import com.sanhua.marketingcost.service.QuoteCostingWorkspaceService;
import com.sanhua.marketingcost.service.QuoteCostRunVersionInvalidationService;
import com.sanhua.marketingcost.service.QuoteEffectiveBomCostingService;
import com.sanhua.marketingcost.service.QuoteProductBomCostingBuildService;
import com.sanhua.marketingcost.service.QuoteProductBomPreparationService;
import com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomFeatureSwitch;
import com.sanhua.marketingcost.service.ingest.QuoteBomStatusService;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuoteCostingWorkbenchServiceImplTest {

  private static final String SAMPLE_OA_NO = "FI-SC-006-20260108-109";
  private static final Long SAMPLE_OA_FORM_ITEM_ID = 180L;
  private static final String SAMPLE_PRODUCT_CODE = "1001900001090";
  private static final String SAMPLE_PERIOD_MONTH = CostPricingPeriodUtils.currentPricingMonth();
  private static final String SAMPLE_BUILD_BATCH_ID = "f_20260609_12fe06";
  private static final BigDecimal SAMPLE_TOTAL_COST = new BigDecimal("137.806217");

  private OaFormMapper oaFormMapper;
  private OaFormItemMapper oaFormItemMapper;
  private QuoteBomStatusMapper quoteBomStatusMapper;
  private BomCostingRowMapper bomCostingRowMapper;
  private BomCostingRowSubRefMapper bomCostingRowSubRefMapper;
  private MaterialMasterRawMapper materialMasterRawMapper;
  private QuoteCostingWorkbenchSummaryMapper workbenchSummaryMapper;
  private QuotePriceTypeRecognitionService priceTypeRecognitionService;
  private QuoteProductBomCostingBuildService costingBuildService;
  private QuoteProductBomPreparationService bomPreparationService;
  private QuoteEffectiveBomCostingService effectiveBomCostingService;
  private QuoteCostingWorkspaceService workspaceService;
  private QuoteCostRunVersionInvalidationService costRunVersionInvalidationService;
  private QuoteBomStatusService quoteBomStatusService;
  private QuoteCostingWorkbenchServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, OaForm.class);
    TableInfoHelper.initTableInfo(assistant, OaFormItem.class);
    TableInfoHelper.initTableInfo(assistant, QuoteBomStatus.class);
    TableInfoHelper.initTableInfo(assistant, BomCostingRow.class);
  }

  @BeforeEach
  void setUp() {
    oaFormMapper = mock(OaFormMapper.class);
    oaFormItemMapper = mock(OaFormItemMapper.class);
    quoteBomStatusMapper = mock(QuoteBomStatusMapper.class);
    bomCostingRowMapper = mock(BomCostingRowMapper.class);
    bomCostingRowSubRefMapper = mock(BomCostingRowSubRefMapper.class);
    materialMasterRawMapper = mock(MaterialMasterRawMapper.class);
    workbenchSummaryMapper = mock(QuoteCostingWorkbenchSummaryMapper.class);
    priceTypeRecognitionService = mock(QuotePriceTypeRecognitionService.class);
    costingBuildService = mock(QuoteProductBomCostingBuildService.class);
    bomPreparationService = mock(QuoteProductBomPreparationService.class);
    effectiveBomCostingService = mock(QuoteEffectiveBomCostingService.class);
    workspaceService = mock(QuoteCostingWorkspaceService.class);
    costRunVersionInvalidationService = mock(QuoteCostRunVersionInvalidationService.class);
    when(workspaceService.find(anyLong(), anyString())).thenReturn(Optional.empty());
    quoteBomStatusService = mock(QuoteBomStatusService.class);
    QuoteBomStatusItemResponse checkedBom = new QuoteBomStatusItemResponse();
    checkedBom.setBomStatus("SYNCED");
    when(quoteBomStatusService.checkItemForCostRun(anyString(), anyLong(), anyString()))
        .thenReturn(checkedBom);
    when(bomPreparationService.prepareByOaFormItem(anyLong(), any(LocalDate.class)))
        .thenReturn(preparation(true));
    service =
        new QuoteCostingWorkbenchServiceImpl(
            oaFormMapper,
            oaFormItemMapper,
            quoteBomStatusMapper,
            bomCostingRowMapper,
            bomCostingRowSubRefMapper,
            materialMasterRawMapper,
            workbenchSummaryMapper,
            costingBuildService,
            bomPreparationService,
            effectiveBomCostingService,
            workspaceService,
            costRunVersionInvalidationService,
            new QuoteEffectiveBomFeatureSwitch(true),
            quoteBomStatusService,
            mock(com.sanhua.marketingcost.service.collaboration.CollaborationCostingGate.class),
            priceTypeRecognitionService);
  }

  @Test
  void existingSnapshotDoesNotBuildAgain() {
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item(10L, "FIN-001"));
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status(SAMPLE_PERIOD_MONTH));
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(List.of(row(10L, "FIN-001", "MAT-1")));

    QuoteCostingWorkbenchResponse response = service.getWorkbench("OA-001", 10L);

    assertThat(response.getSnapshotGenerated()).isFalse();
    assertThat(response.getEffectiveBomEnabled()).isTrue();
    assertThat(response.getPeriodMonth()).isEqualTo(SAMPLE_PERIOD_MONTH);
    assertThat(response.getBomRows()).hasSize(1);
    assertThat(response.getBomRows().get(0).getOaFormItemId()).isEqualTo(10L);
    assertThat(response.getBomRows().get(0).getChildCode()).isEqualTo("MAT-1");
    assertThat(response.getTabs()).extracting("code")
        .containsExactly(
            "PRODUCT_DETAIL", "QUOTE_BOM", "PRICE_TYPE_CONFIRMATION", "PRICE_PREPARE", "COST_RUN");
    assertThat(response.getTabs()).extracting("status")
        .containsExactly("DONE", "DONE", "PENDING", "BLOCKED", "BLOCKED");
    assertThat(response.getWorkflowStatus().getCurrentBlockedStep())
        .isEqualTo("PRICE_TYPE_CONFIRMATION");
    verify(costingBuildService, never()).buildByOaFormItem(any());
  }

  @Test
  void rollupParentCarriesMatchedChildForCombinedDisplayNameWithoutAddingBomRows() {
    BomCostingRow parent = row(10L, "FIN-001", "201190083");
    parent.setMaterialName("接管");
    parent.setMaterialSpec("T-JG-0029");
    parent.setMaterialOrganizationCode("COMMERCIAL");
    parent.setSettlementRowType("SPECIAL_ROLLUP_PARENT");
    BomCostingRowSubRef ref = new BomCostingRowSubRef();
    ref.setCostingRowId(parent.getId());
    ref.setRefType("SPECIAL_ROLLUP_CHILD");
    ref.setSubMaterialCode("301050120");
    ref.setSubMaterialName("拉制铜管");
    ref.setSubQtyPerParent(new BigDecimal("0.00381546"));
    ref.setSubQtyPerTop(new BigDecimal("0.00381546"));
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item(10L, "FIN-001"));
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status(SAMPLE_PERIOD_MONTH));
    when(bomCostingRowMapper.selectQuoteCostingSnapshot(
            "OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(List.of(parent));
    when(bomCostingRowSubRefMapper.selectSpecialRollupChildren(List.of(parent.getId())))
        .thenReturn(List.of(ref));
    MaterialMasterRaw parentArchive = new MaterialMasterRaw();
    parentArchive.setMaterialCode("201190083");
    parentArchive.setDrawingNo("T-JG-0029");
    MaterialMasterRaw childArchive = new MaterialMasterRaw();
    childArchive.setMaterialCode("301050120");
    childArchive.setMaterialName("拉制铜管");
    childArchive.setDrawingNo("CHILD-DRAWING-001");
    childArchive.setUnit("千克");
    when(materialMasterRawMapper.selectByLatestBatchAndCodes(any(), isNull(), eq("COMMERCIAL")))
        .thenReturn(List.of(parentArchive, childArchive));

    QuoteCostingWorkbenchResponse response = service.getWorkbench("OA-001", 10L);

    assertThat(response.getBomRows()).singleElement().satisfies(row -> {
      assertThat(row.getChildCode()).isEqualTo("201190083");
      assertThat(row.getChildName()).isEqualTo("接管");
      assertThat(row.getRollupComponents()).singleElement().satisfies(component -> {
        assertThat(component.getChildCode()).isEqualTo("301050120");
        assertThat(component.getChildName()).isEqualTo("拉制铜管");
        assertThat(component.getChildDrawingNo()).isEqualTo("CHILD-DRAWING-001");
        assertThat(component.getParentDrawingNo()).isEqualTo("T-JG-0029");
        assertThat(component.getUsageQty()).isEqualByComparingTo("0.00381546");
        assertThat(component.getUnit()).isEqualTo("千克");
      });
    });
  }

  @Test
  void rollupDisplayNormalizesRepeatedChildUsageAgainstMergedParentQuantity() {
    BomCostingRow parent = row(10L, "FIN-001", "1053000301622");
    parent.setMaterialOrganizationCode("COMMERCIAL");
    parent.setSettlementRowType("SPECIAL_ROLLUP_PARENT");
    parent.setQtyPerTop(new BigDecimal("4"));
    BomCostingRowSubRef first = new BomCostingRowSubRef();
    first.setCostingRowId(parent.getId());
    first.setRefType("SPECIAL_ROLLUP_CHILD");
    first.setSubMaterialCode("301260124");
    first.setSubMaterialName("不锈钢板");
    first.setSubQtyPerParent(new BigDecimal("0.0053"));
    first.setSubQtyPerTop(new BigDecimal("0.0106"));
    BomCostingRowSubRef second = new BomCostingRowSubRef();
    second.setCostingRowId(parent.getId());
    second.setRefType("SPECIAL_ROLLUP_CHILD");
    second.setSubMaterialCode("301260124");
    second.setSubMaterialName("不锈钢板");
    second.setSubQtyPerParent(new BigDecimal("0.0053"));
    second.setSubQtyPerTop(new BigDecimal("0.0106"));
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item(10L, "FIN-001"));
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status(SAMPLE_PERIOD_MONTH));
    when(bomCostingRowMapper.selectQuoteCostingSnapshot(
            "OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(List.of(parent));
    when(bomCostingRowSubRefMapper.selectSpecialRollupChildren(List.of(parent.getId())))
        .thenReturn(List.of(first, second));

    QuoteCostingWorkbenchResponse response = service.getWorkbench("OA-001", 10L);

    assertThat(response.getBomRows()).singleElement().satisfies(row ->
        assertThat(row.getRollupComponents()).singleElement().satisfies(component -> {
          assertThat(component.getUsageQty()).isEqualByComparingTo("0.0053");
          assertThat(component.getQtyPerTop()).isEqualByComparingTo("0.0212");
        }));
  }

  @Test
  void pageReadDoesNotCreateMissingBomSnapshot() {
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item(10L, "FIN-001"));
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status(SAMPLE_PERIOD_MONTH));
    when(bomCostingRowMapper.selectQuoteCostingSnapshot(
            "OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(List.of());

    QuoteCostingWorkbenchResponse response = service.getWorkbench("OA-001", 10L);

    assertThat(response.getSnapshotGenerated()).isFalse();
    assertThat(response.getBomRows()).isEmpty();
    verify(costingBuildService, never())
        .buildByOaFormItem(anyLong(), anyString(), any(LocalDate.class));
  }

  @Test
  void existingSnapshotRendersRowsWhenRawHierarchyIdIsStaleAfterCutover() {
    BomCostingRow staleRawIdRow = row(10L, "FIN-001", "HISTORY-MAT");
    staleRawIdRow.setRawHierarchyNodeId(9_999_999L);
    staleRawIdRow.setParentCode("HISTORY-PARENT");
    staleRawIdRow.setMaterialName("历史快照子件");
    staleRawIdRow.setMaterialSpec("HISTORY-SPEC");
    staleRawIdRow.setQtyPerParent(new BigDecimal("2.50000000"));
    staleRawIdRow.setQtyPerTop(new BigDecimal("5.00000000"));
    staleRawIdRow.setPath("/FIN-001/HISTORY-PARENT/HISTORY-MAT/");
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item(10L, "FIN-001"));
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status(SAMPLE_PERIOD_MONTH));
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(List.of(staleRawIdRow));

    QuoteCostingWorkbenchResponse response = service.getWorkbench("OA-001", 10L);

    assertThat(response.getSnapshotGenerated()).isFalse();
    assertThat(response.getBomRows()).singleElement().satisfies(row -> {
      assertThat(row.getParentCode()).isEqualTo("HISTORY-PARENT");
      assertThat(row.getChildCode()).isEqualTo("HISTORY-MAT");
      assertThat(row.getChildName()).isEqualTo("历史快照子件");
      assertThat(row.getChildModel()).isEqualTo("HISTORY-SPEC");
      assertThat(row.getUsageQty()).isEqualByComparingTo("2.50000000");
      assertThat(row.getQtyPerTop()).isEqualByComparingTo("5.00000000");
      assertThat(row.getPath()).isEqualTo("/FIN-001/HISTORY-PARENT/HISTORY-MAT/");
    });
    verify(costingBuildService, never()).buildByOaFormItem(any());
  }

  @Test
  void launchWorkbenchExplicitlyRebuildsCurrentBom() {
    BomCostingRow rebuilt = row(10L, "FIN-001", "MAT-NEW");
    rebuilt.setBuiltAt(LocalDateTime.of(2026, 6, 30, 11, 0));
    rebuilt.setBuildBatchId("forced_refresh_batch");
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item(10L, "FIN-001"));
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status(SAMPLE_PERIOD_MONTH));
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(List.of(rebuilt));
    when(effectiveBomCostingService.prepareCurrent("OA-001", 10L))
        .thenReturn(buildResponse("forced_refresh_batch"));

    QuoteCostingWorkbenchResponse response = service.launchWorkbench("OA-001", 10L);

    verify(oaFormMapper).selectIdForCostingUpdate("OA-001");
    assertThat(response.getSnapshotGenerated()).isTrue();
    assertThat(response.getBomRows()).hasSize(1);
    assertThat(response.getBomRows().get(0).getChildCode()).isEqualTo("MAT-NEW");
    verify(effectiveBomCostingService).prepareCurrent("OA-001", 10L);
    verify(bomPreparationService).prepareByOaFormItem(10L, LocalDate.now());
    verify(quoteBomStatusService).checkItemForCostRun("OA-001", 10L, SAMPLE_PERIOD_MONTH);
    verify(costRunVersionInvalidationService)
        .invalidateProduct("OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH);
  }

  @Test
  void missingBomMarksOnlyCurrentWorkspaceWaitingAndPreservesHistoryPointers() {
    OaFormItem item = item(10L, "FIN-001");
    item.setBusinessUnitType("COMMERCIAL");
    QuoteBomStatusItemResponse missingBom = new QuoteBomStatusItemResponse();
    missingBom.setBomStatus("MISSING");
    QuoteCostingWorkspace workspace = workspace("BOM-BUILD-OLD", "BOM_READY");
    workspace.setCurrentCostVersionId(88L);
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item);
    when(quoteBomStatusService.checkItemForCostRun("OA-001", 10L, SAMPLE_PERIOD_MONTH))
        .thenReturn(missingBom);
    when(workspaceService.lockOrCreate(
            "OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH, "COMMERCIAL"))
        .thenReturn(workspace);
    when(workspaceService.update(workspace, 4)).thenReturn(workspace);
    when(workspaceService.find(10L, SAMPLE_PERIOD_MONTH)).thenReturn(Optional.of(workspace));
    when(bomCostingRowMapper.selectQuoteCostingSnapshot(
            "OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(List.of(row(10L, "FIN-001", "HISTORY-MAT")));

    QuoteCostingWorkbenchResponse response = service.launchWorkbench("OA-001", 10L);

    assertThat(response.getSnapshotGenerated()).isFalse();
    assertThat(response.getCostingWorkspace().getWorkspaceStatus()).isEqualTo("WAIT_BOM");
    assertThat(response.getWorkflowStatus().getQuoteBomStatus()).isEqualTo("BLOCKED");
    assertThat(workspace.getCurrentBomBuildBatchId()).isEqualTo("BOM-BUILD-OLD");
    assertThat(workspace.getCurrentCostVersionId()).isEqualTo(88L);
    verify(effectiveBomCostingService, never()).prepareCurrent(anyString(), anyLong());
    verify(bomPreparationService, never()).prepareByOaFormItem(anyLong(), any(LocalDate.class));
    verify(workspaceService).update(workspace, 4);
  }

  @Test
  void preparationGapAfterPositiveStatusBecomesWaitingForBomInsteadOfSystemFailure() {
    OaFormItem item = item(10L, "FIN-001");
    item.setBusinessUnitType("COMMERCIAL");
    QuoteCostingWorkspace workspace = workspace(null, "NOT_STARTED");
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item);
    when(bomPreparationService.prepareByOaFormItem(10L, LocalDate.now()))
        .thenReturn(preparation(false));
    when(workspaceService.lockOrCreate(
            "OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH, "COMMERCIAL"))
        .thenReturn(workspace);
    when(workspaceService.update(workspace, 4)).thenReturn(workspace);
    when(workspaceService.find(10L, SAMPLE_PERIOD_MONTH)).thenReturn(Optional.of(workspace));

    QuoteCostingWorkbenchResponse response = service.launchWorkbench("OA-001", 10L);

    assertThat(response.getSnapshotGenerated()).isFalse();
    assertThat(response.getCostingWorkspace().getWorkspaceStatus()).isEqualTo("WAIT_BOM");
    assertThat(workspace.getLastErrorCode()).isEqualTo("BOM_MISSING");
    verify(effectiveBomCostingService, never()).prepareCurrent(anyString(), anyLong());
  }

  private QuoteProductBomPreparationPreview preparation(boolean ready) {
    return new QuoteProductBomPreparationPreview(
        null,
        null,
        1L,
        10L,
        "OA-001",
        "FIN-001",
        "NON_BARE",
        null,
        false,
        SAMPLE_PERIOD_MONTH,
        ready ? "READY" : "NEED_TECH",
        null,
        ready,
        !ready,
        false,
        "RAW_PRODUCT_BOM",
        ready,
        0,
        null,
        null,
        false,
        0,
        null,
        null,
        null,
        null,
        null,
        null,
        List.of(),
        ready ? List.of() : List.of("当前产品没有可用于核算的 BOM"),
        null,
        List.of(),
        List.of());
  }

  @Test
  void staleWorkspaceKeepsCurrentRowsVisibleButBlocksDownstream() {
    QuoteCostingWorkspace workspace = workspace("BOM-BUILD-001", "STALE");
    workspace.setStaleReasonCode("BOM_ALTERNATIVE_CHANGED");
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item(10L, "FIN-001"));
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status(SAMPLE_PERIOD_MONTH));
    when(workspaceService.find(10L, SAMPLE_PERIOD_MONTH)).thenReturn(Optional.of(workspace));
    when(bomCostingRowMapper.selectQuoteCostingSnapshot(
            "OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(List.of(row(10L, "FIN-001", "MAT-1")));

    QuoteCostingWorkbenchResponse response = service.getWorkbench("OA-001", 10L);

    assertThat(response.getBomRows()).hasSize(1);
    assertThat(response.getCostingWorkspace().getStaleReasonCode())
        .isEqualTo("BOM_ALTERNATIVE_CHANGED");
    assertThat(response.getWorkflowStatus().getQuoteBomStatus()).isEqualTo("STALE");
    assertThat(response.getWorkflowStatus().getPriceTypeConfirmationStatus())
        .isEqualTo("BLOCKED");
  }

  @Test
  void launchWorkbenchRebuildsLegacyPlatePurchaseRowWhenCommercialMasterIsManufactured() {
    BomCostingRow oldRow = row(10L, "FIN-001", "9990000050426");
    oldRow.setBuiltAt(LocalDateTime.of(2026, 7, 9, 20, 6));
    oldRow.setShapeAttr("采购件");
    oldRow.setPriceOrgCode("220");
    oldRow.setMaterialOrganizationCode("PLATE");
    BomCostingRow rebuiltRow = row(10L, "FIN-001", "9990000050426");
    rebuiltRow.setBuiltAt(LocalDateTime.of(2026, 7, 10, 13, 0));
    rebuiltRow.setBuildBatchId("cross_org_batch");
    rebuiltRow.setShapeAttr("制造件");
    rebuiltRow.setPriceOrgCode("210");
    rebuiltRow.setMaterialOrganizationCode("COMMERCIAL");
    MaterialMasterRaw commercialMaster = new MaterialMasterRaw();
    commercialMaster.setMaterialCode("9990000050426");
    commercialMaster.setShapeAttr("制造件");

    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item(10L, "FIN-001"));
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status(SAMPLE_PERIOD_MONTH));
    when(bomCostingRowMapper.selectQuoteCostingSnapshot(
            "OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(List.of(rebuiltRow));
    when(materialMasterRawMapper.selectByLatestBatchAndCodes(
            any(), isNull(), eq("COMMERCIAL")))
        .thenReturn(List.of(commercialMaster));
    when(effectiveBomCostingService.prepareCurrent("OA-001", 10L))
        .thenReturn(buildResponse("cross_org_batch"));

    QuoteCostingWorkbenchResponse response = service.launchWorkbench("OA-001", 10L);

    assertThat(response.getSnapshotGenerated()).isTrue();
    assertThat(response.getBomRows()).hasSize(1);
    QuoteCostingWorkbenchBomRowResponse row = response.getBomRows().get(0);
    assertThat(row.getChildCode()).isEqualTo("9990000050426");
    assertThat(row.getShapeAttribute()).isEqualTo("制造件");
    assertThat(row.getPriceOrgCode()).isEqualTo("210");
    assertThat(row.getMaterialOrganizationCode()).isEqualTo("COMMERCIAL");
    verify(effectiveBomCostingService).prepareCurrent("OA-001", 10L);
  }

  @Test
  void currentBomRowsMakeQuoteBomDoneAndPriceTypePending() {
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item(10L, "FIN-001"));
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status(SAMPLE_PERIOD_MONTH));
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(List.of(row(10L, "FIN-001", "MAT-1")));

    QuoteCostingWorkbenchResponse response = service.getWorkbench("OA-001", 10L);

    assertThat(response.getWorkflowStatus().getQuoteBomStatus()).isEqualTo("DONE");
    assertThat(response.getWorkflowStatus().getPriceTypeConfirmationStatus()).isEqualTo("PENDING");
    assertThat(response.getTabs()).extracting("code", "status")
        .contains(
            org.assertj.core.groups.Tuple.tuple("QUOTE_BOM", "DONE"),
            org.assertj.core.groups.Tuple.tuple("PRICE_TYPE_CONFIRMATION", "PENDING"));
  }

  @Test
  void currentRoutesAutomaticallyCompletePriceTypeStepWithoutConfirmationBatch() {
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item(10L, "FIN-001"));
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status(SAMPLE_PERIOD_MONTH));
    when(bomCostingRowMapper.selectQuoteCostingSnapshot(
        "OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(List.of(row(10L, "FIN-001", "MAT-1")));
    when(priceTypeRecognitionService.getRecognition("OA-001", 10L, SAMPLE_PERIOD_MONTH))
        .thenReturn(recognition(0));

    QuoteCostingWorkbenchResponse response = service.getWorkbench("OA-001", 10L);

    assertThat(response.getLatestPriceTypeRecognition().getStatus()).isEqualTo("AUTO_READY");
    assertThat(response.getWorkflowStatus().getPriceTypeConfirmationStatus()).isEqualTo("DONE");
    assertThat(response.getTabs())
        .filteredOn(tab -> "PRICE_TYPE_CONFIRMATION".equals(tab.getCode()))
        .extracting("name", "status")
        .containsExactly(org.assertj.core.groups.Tuple.tuple("价格类型识别", "DONE"));
  }

  @Test
  void priceTypeGapMakesPriceTypePartial() {
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item(10L, "FIN-001"));
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status(SAMPLE_PERIOD_MONTH));
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(List.of(row(10L, "FIN-001", "MAT-1")));
    when(priceTypeRecognitionService.getRecognition("OA-001", 10L, SAMPLE_PERIOD_MONTH))
        .thenReturn(recognition(2));

    QuoteCostingWorkbenchResponse response = service.getWorkbench("OA-001", 10L);

    assertThat(response.getWorkflowStatus().getPriceTypeConfirmationStatus()).isEqualTo("PARTIAL");
    assertThat(response.getWorkflowStatus().getPricePrepareStatus()).isEqualTo("BLOCKED");
  }

  @Test
  void pricePrepareGapMakesPreparePartial() {
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item(10L, "FIN-001"));
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status(SAMPLE_PERIOD_MONTH));
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(List.of(row(10L, "FIN-001", "MAT-1")));
    when(priceTypeRecognitionService.getRecognition("OA-001", 10L, SAMPLE_PERIOD_MONTH))
        .thenReturn(recognition(0));
    when(workbenchSummaryMapper.selectLatestPricePrepare("OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(pricePrepare("PARTIAL", 1));

    QuoteCostingWorkbenchResponse response = service.getWorkbench("OA-001", 10L);

    assertThat(response.getWorkflowStatus().getPricePrepareStatus()).isEqualTo("PARTIAL");
    assertThat(response.getWorkflowStatus().getCostRunStatus()).isEqualTo("BLOCKED");
  }

  @Test
  void trialCostRunStaysBlockedWhenPrepareStillHasWarnings() {
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item(10L, "FIN-001"));
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status(SAMPLE_PERIOD_MONTH));
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(List.of(row(10L, "FIN-001", "MAT-1")));
    when(priceTypeRecognitionService.getRecognition("OA-001", 10L, SAMPLE_PERIOD_MONTH))
        .thenReturn(recognition(0));
    when(workbenchSummaryMapper.selectLatestPricePrepare("OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(pricePrepare("PARTIAL", 1));
    when(workbenchSummaryMapper.selectLatestCostRun("OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(costRun("TRIAL"));

    QuoteCostingWorkbenchResponse response = service.getWorkbench("OA-001", 10L);

    assertThat(response.getWorkflowStatus().getPricePrepareStatus()).isEqualTo("PARTIAL");
    assertThat(response.getWorkflowStatus().getCostRunStatus()).isEqualTo("BLOCKED");
    assertThat(response.getWorkflowStatus().getOverallStatus()).isEqualTo("BLOCKED");
    assertThat(response.getWorkflowStatus().getCurrentBlockedStep()).isEqualTo("PRICE_PREPARE");
  }

  @Test
  void confirmedCostRunMakesCostRunDone() {
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item(10L, "FIN-001"));
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status(SAMPLE_PERIOD_MONTH));
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(List.of(row(10L, "FIN-001", "MAT-1")));
    when(priceTypeRecognitionService.getRecognition("OA-001", 10L, SAMPLE_PERIOD_MONTH))
        .thenReturn(recognition(0));
    when(workbenchSummaryMapper.selectLatestPricePrepare("OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(pricePrepare("SUCCESS", 0));
    QuoteCostRunSummaryResponse completed = costRun("CONFIRMED");
    completed.setPricePrepareNo("FINANCE-PP-001");
    completed.setOaPricePrepareNo("PP-001");
    when(workbenchSummaryMapper.selectLatestCostRun("OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(completed);

    QuoteCostingWorkbenchResponse response = service.getWorkbench("OA-001", 10L);

    assertThat(response.getLatestCostRun().getVersionNo()).isEqualTo("COST-001-V1");
    assertThat(response.getWorkflowStatus().getCostRunStatus()).isEqualTo("DONE");
    assertThat(response.getWorkflowStatus().getOverallStatus()).isEqualTo("DONE");
  }

  @Test
  void explicitLaunchBuildsMissingSnapshotThenReturnsRows() {
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item(10L, "FIN-001"));
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status(SAMPLE_PERIOD_MONTH));
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(List.of(row(10L, "FIN-001", "MAT-1")));
    when(effectiveBomCostingService.prepareCurrent("OA-001", 10L))
        .thenReturn(
            new QuoteBomCostingBuildResponse(
                201L,
                null,
                10L,
                "OA-001",
                "FIN-001",
                "NON_BARE",
                SAMPLE_PERIOD_MONTH,
                "qbp_20260608_abcd1234",
                1,
                1,
                0,
                Map.of("RAW_PRODUCT_BOM", 1),
                List.of(),
                LocalDateTime.now()));

    QuoteCostingWorkbenchResponse response = service.launchWorkbench("OA-001", 10L);

    assertThat(response.getSnapshotGenerated()).isTrue();
    assertThat(response.getBuildBatchId()).isEqualTo("qbp_20260608_abcd1234");
    assertThat(response.getBomRows()).extracting("childCode").containsExactly("MAT-1");
    verify(effectiveBomCostingService).prepareCurrent("OA-001", 10L);
  }

  @Test
  void snapshotQueryUsesQuoteItemProductAndPeriodScope() {
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(20L)).thenReturn(item(20L, "FIN-002"));
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status(SAMPLE_PERIOD_MONTH));
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 20L, "FIN-002", SAMPLE_PERIOD_MONTH))
        .thenReturn(List.of(row(20L, "FIN-002", "MAT-2")));

    service.getWorkbench("OA-001", 20L);

    verify(bomCostingRowMapper)
        .selectQuoteCostingSnapshot("OA-001", 20L, "FIN-002", SAMPLE_PERIOD_MONTH);
  }

  @Test
  void goldenSampleWorkbenchUsesProductLineScopeBeforeWorkbenchRemodel() {
    // QWB-00 基线：真实库当前成本结果为 137.806217，工作台必须按产品行读取成本版本摘要。
    when(oaFormMapper.selectOne(any())).thenReturn(sampleForm());
    when(oaFormItemMapper.selectById(SAMPLE_OA_FORM_ITEM_ID))
        .thenReturn(sampleItem());
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(sampleStatus());
    when(
            bomCostingRowMapper.selectQuoteCostingSnapshot(
                SAMPLE_OA_NO, SAMPLE_OA_FORM_ITEM_ID, SAMPLE_PRODUCT_CODE, SAMPLE_PERIOD_MONTH))
        .thenReturn(sampleRows());
    when(
            workbenchSummaryMapper.selectLatestCostRun(
                SAMPLE_OA_NO, SAMPLE_OA_FORM_ITEM_ID, SAMPLE_PRODUCT_CODE, SAMPLE_PERIOD_MONTH))
        .thenReturn(sampleCostRun());

    QuoteCostingWorkbenchResponse response =
        service.getWorkbench(SAMPLE_OA_NO, SAMPLE_OA_FORM_ITEM_ID);

    assertThat(response.getHeader().getOaNo()).isEqualTo(SAMPLE_OA_NO);
    assertThat(response.getItem().getId()).isEqualTo(SAMPLE_OA_FORM_ITEM_ID);
    assertThat(response.getItem().getMaterialNo()).isEqualTo(SAMPLE_PRODUCT_CODE);
    assertThat(response.getPeriodMonth()).isEqualTo(SAMPLE_PERIOD_MONTH);
    assertThat(response.getBuildBatchId()).isEqualTo(SAMPLE_BUILD_BATCH_ID);
    assertThat(response.getLatestCostRun()).isNotNull();
    assertThat(response.getLatestCostRun().getOaFormItemId()).isEqualTo(SAMPLE_OA_FORM_ITEM_ID);
    assertThat(response.getLatestCostRun().getProductCode()).isEqualTo(SAMPLE_PRODUCT_CODE);
    assertThat(response.getLatestCostRun().getTotalCost()).isEqualByComparingTo(SAMPLE_TOTAL_COST);
    assertThat(response.getSnapshotGenerated()).isFalse();
    assertThat(response.getBomRows()).hasSize(26);
    assertThat(response.getBomRows())
        .allSatisfy(
            row -> {
              assertThat(row.getOaNo()).isEqualTo(SAMPLE_OA_NO);
              assertThat(row.getOaFormItemId()).isEqualTo(SAMPLE_OA_FORM_ITEM_ID);
              assertThat(row.getTopProductCode()).isEqualTo(SAMPLE_PRODUCT_CODE);
            });
    verify(bomCostingRowMapper)
        .selectQuoteCostingSnapshot(
            SAMPLE_OA_NO, SAMPLE_OA_FORM_ITEM_ID, SAMPLE_PRODUCT_CODE, SAMPLE_PERIOD_MONTH);
    verify(workbenchSummaryMapper)
        .selectLatestCostRun(
            SAMPLE_OA_NO, SAMPLE_OA_FORM_ITEM_ID, SAMPLE_PRODUCT_CODE, SAMPLE_PERIOD_MONTH);
    verify(costingBuildService, never()).buildByOaFormItem(any());
  }

  private OaForm form() {
    OaForm form = new OaForm();
    form.setId(1L);
    form.setOaNo("OA-001");
    form.setAccountingPeriodMonth(SAMPLE_PERIOD_MONTH);
    form.setCustomer("客户A");
    return form;
  }

  private OaForm sampleForm() {
    OaForm form = new OaForm();
    form.setId(101L);
    form.setOaNo(SAMPLE_OA_NO);
    form.setAccountingPeriodMonth(SAMPLE_PERIOD_MONTH);
    form.setCustomer("QWB-00 样例客户");
    return form;
  }

  private OaFormItem item(Long id, String productCode) {
    OaFormItem item = new OaFormItem();
    item.setId(id);
    item.setOaFormId(1L);
    item.setSeq(id.intValue());
    item.setMaterialNo(productCode);
    item.setProductName(productCode + " name");
    return item;
  }

  private OaFormItem sampleItem() {
    OaFormItem item = new OaFormItem();
    item.setId(SAMPLE_OA_FORM_ITEM_ID);
    item.setOaFormId(101L);
    item.setSeq(14);
    item.setMaterialNo(SAMPLE_PRODUCT_CODE);
    item.setProductName("QWB-00 样例产品");
    return item;
  }

  private QuoteBomStatus status(String periodMonth) {
    QuoteBomStatus status = new QuoteBomStatus();
    status.setId(101L);
    status.setOaNo("OA-001");
    status.setOaFormItemId(10L);
    status.setProductCode("FIN-001");
    status.setBomStatus("SYNCED");
    status.setCostPeriodMonth(periodMonth);
    return status;
  }

  private QuoteBomStatus sampleStatus() {
    QuoteBomStatus status = new QuoteBomStatus();
    status.setId(202L);
    status.setOaNo(SAMPLE_OA_NO);
    status.setOaFormItemId(SAMPLE_OA_FORM_ITEM_ID);
    status.setProductCode(SAMPLE_PRODUCT_CODE);
    status.setBomStatus("SYNCED");
    status.setCostPeriodMonth(SAMPLE_PERIOD_MONTH);
    return status;
  }

  private QuoteCostingWorkspace workspace(String buildBatchId, String status) {
    QuoteCostingWorkspace workspace = new QuoteCostingWorkspace();
    workspace.setId(1L);
    workspace.setOaNo("OA-001");
    workspace.setOaFormItemId(10L);
    workspace.setProductCode("FIN-001");
    workspace.setPeriodMonth(SAMPLE_PERIOD_MONTH);
    workspace.setCurrentBomBuildBatchId(buildBatchId);
    workspace.setWorkspaceStatus(status);
    workspace.setLockVersion(4);
    return workspace;
  }

  private QuotePriceTypeRecognitionResponse recognition(int gaps) {
    QuotePriceTypeRecognitionSummary summary = new QuotePriceTypeRecognitionSummary();
    summary.setReadyForPricePrepareCount(3);
    summary.setConfiguredTypeCount(3 - gaps);
    summary.setMissingTypeCount(gaps);
    summary.setReferencePriceCount(2);
    QuotePriceTypeRecognitionResponse response = new QuotePriceTypeRecognitionResponse();
    response.setOaNo("OA-001");
    response.setOaFormItemId(10L);
    response.setProductCode("FIN-001");
    response.setPeriodMonth(SAMPLE_PERIOD_MONTH);
    response.setBomBuildBatchId("BOM-BUILD-001");
    response.setSummary(summary);
    return response;
  }

  private QuotePricePrepareSummaryResponse pricePrepare(String status, int gaps) {
    QuotePricePrepareSummaryResponse response = new QuotePricePrepareSummaryResponse();
    response.setId(901L);
    response.setPrepareNo("PP-001");
    response.setOaNo("OA-001");
    response.setOaFormItemId(10L);
    response.setTopProductCode("FIN-001");
    response.setPeriodMonth(SAMPLE_PERIOD_MONTH);
    response.setStatus(status);
    response.setGapCount(gaps);
    return response;
  }

  private QuoteCostRunSummaryResponse costRun(String status) {
    QuoteCostRunSummaryResponse response = new QuoteCostRunSummaryResponse();
    response.setId(1001L);
    response.setCostRunNo("TRIAL-001");
    response.setVersionNo("COST-001-V1");
    response.setOaNo("OA-001");
    response.setOaFormItemId(10L);
    response.setProductCode("FIN-001");
    response.setResultPeriod(SAMPLE_PERIOD_MONTH);
    response.setPricePrepareNo("PP-001");
    response.setStatus(status);
    response.setTotalCost(new BigDecimal("12.345678"));
    return response;
  }

  private QuoteCostRunSummaryResponse sampleCostRun() {
    QuoteCostRunSummaryResponse response = new QuoteCostRunSummaryResponse();
    response.setId(13_700L);
    response.setCostRunNo("TRIAL-FI-SC-006-20260108-109-180");
    response.setVersionNo("COST-FI-SC-006-20260108-109-180-V1");
    response.setOaNo(SAMPLE_OA_NO);
    response.setOaFormItemId(SAMPLE_OA_FORM_ITEM_ID);
    response.setProductCode(SAMPLE_PRODUCT_CODE);
    response.setResultPeriod(SAMPLE_PERIOD_MONTH);
    response.setStatus("CONFIRMED");
    response.setTotalCost(SAMPLE_TOTAL_COST);
    return response;
  }

  private BomCostingRow row(Long itemId, String topProductCode, String materialCode) {
    BomCostingRow row = new BomCostingRow();
    row.setId((long) materialCode.hashCode());
    row.setOaNo("OA-001");
    row.setOaFormItemId(itemId);
    row.setTopProductCode(topProductCode);
    row.setParentCode(topProductCode);
    row.setMaterialCode(materialCode);
    row.setMaterialName(materialCode + " name");
    row.setMaterialSpec("SPEC");
    row.setQtyPerParent(BigDecimal.ONE);
    row.setQtyPerTop(BigDecimal.ONE);
    row.setPeriodMonth(SAMPLE_PERIOD_MONTH);
    row.setBuildBatchId("existing_batch");
    row.setPath("/" + topProductCode + "/" + materialCode + "/");
    row.setManualModified(0);
    return row;
  }

  private QuoteBomCostingBuildResponse buildResponse(String buildBatchId) {
    return new QuoteBomCostingBuildResponse(
        201L,
        null,
        10L,
        "OA-001",
        "FIN-001",
        "NON_BARE",
        SAMPLE_PERIOD_MONTH,
        buildBatchId,
        1,
        1,
        0,
        Map.of("RAW_PRODUCT_BOM", 1),
        List.of(),
        LocalDateTime.of(2026, 6, 30, 11, 0));
  }

  private List<BomCostingRow> sampleRows() {
    List<BomCostingRow> rows = new java.util.ArrayList<>();
    for (int i = 1; i <= 26; i++) {
      BomCostingRow row = new BomCostingRow();
      row.setId(1_000L + i);
      row.setOaNo(SAMPLE_OA_NO);
      row.setOaFormItemId(SAMPLE_OA_FORM_ITEM_ID);
      row.setTopProductCode(SAMPLE_PRODUCT_CODE);
      row.setParentCode(SAMPLE_PRODUCT_CODE);
      row.setMaterialCode("QWB00-MAT-" + String.format("%02d", i));
      row.setMaterialName("QWB-00 样例子件 " + i);
      row.setMaterialSpec("SPEC-" + i);
      row.setQtyPerParent(BigDecimal.ONE);
      row.setQtyPerTop(BigDecimal.ONE);
      row.setPeriodMonth(SAMPLE_PERIOD_MONTH);
      row.setBuildBatchId(SAMPLE_BUILD_BATCH_ID);
      row.setPath("/" + SAMPLE_PRODUCT_CODE + "/" + String.format("%02d", i) + "/");
      row.setManualModified(0);
      rows.add(row);
    }
    return rows;
  }

}
