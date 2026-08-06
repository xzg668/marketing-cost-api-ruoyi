package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.sanhua.marketingcost.dto.quotecosting.QuoteBomConfirmationSummaryResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunSummaryResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostingWorkbenchBomRowResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostingWorkbenchResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareSummaryResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeConfirmationSummaryResponse;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomCostingRowSubRef;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.BomSettlementRule;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteBomConfirmation;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import com.sanhua.marketingcost.entity.QuotePriceTypeConfirmBatch;
import com.sanhua.marketingcost.mapper.BomByproductCostRuleMapper;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.BomCostingRowSubRefMapper;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.BomSettlementRuleMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomConfirmationMapper;
import com.sanhua.marketingcost.mapper.QuoteBomStatusMapper;
import com.sanhua.marketingcost.mapper.QuoteCostingWorkbenchSummaryMapper;
import com.sanhua.marketingcost.mapper.QuotePriceTypeConfirmBatchMapper;
import com.sanhua.marketingcost.service.QuoteProductBomCostingBuildService;
import com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomFeatureSwitch;
import com.sanhua.marketingcost.service.ingest.QuoteBomStatusService;
import com.sanhua.marketingcost.service.QuoteCostRunVersionInvalidationService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import com.sanhua.marketingcost.service.rule.BomSettlementRuleMatcher;
import com.sanhua.marketingcost.service.rule.BomRuleMaterialAttributeResolver;
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
import org.mockito.ArgumentCaptor;

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
  private BomRawHierarchyMapper bomRawHierarchyMapper;
  private MaterialMasterRawMapper materialMasterRawMapper;
  private BomSettlementRuleMapper settlementRuleMapper;
  private BomByproductCostRuleMapper byproductCostRuleMapper;
  private QuoteBomConfirmationMapper quoteBomConfirmationMapper;
  private QuoteCostingWorkbenchSummaryMapper workbenchSummaryMapper;
  private QuotePriceTypeConfirmBatchMapper priceTypeConfirmBatchMapper;
  private QuoteProductBomCostingBuildService costingBuildService;
  private BomSettlementRuleMatcher settlementRuleMatcher;
  private BomRuleMaterialAttributeResolver materialAttributeResolver;
  private QuoteCostRunVersionInvalidationService versionInvalidationService;
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
    TableInfoHelper.initTableInfo(assistant, BomRawHierarchy.class);
    TableInfoHelper.initTableInfo(assistant, BomSettlementRule.class);
    TableInfoHelper.initTableInfo(assistant, QuoteBomConfirmation.class);
    TableInfoHelper.initTableInfo(assistant, QuotePriceTypeConfirmBatch.class);
  }

  @BeforeEach
  void setUp() {
    oaFormMapper = mock(OaFormMapper.class);
    oaFormItemMapper = mock(OaFormItemMapper.class);
    quoteBomStatusMapper = mock(QuoteBomStatusMapper.class);
    bomCostingRowMapper = mock(BomCostingRowMapper.class);
    bomCostingRowSubRefMapper = mock(BomCostingRowSubRefMapper.class);
    bomRawHierarchyMapper = mock(BomRawHierarchyMapper.class);
    materialMasterRawMapper = mock(MaterialMasterRawMapper.class);
    settlementRuleMapper = mock(BomSettlementRuleMapper.class);
    byproductCostRuleMapper = mock(BomByproductCostRuleMapper.class);
    quoteBomConfirmationMapper = mock(QuoteBomConfirmationMapper.class);
    workbenchSummaryMapper = mock(QuoteCostingWorkbenchSummaryMapper.class);
    priceTypeConfirmBatchMapper = mock(QuotePriceTypeConfirmBatchMapper.class);
    costingBuildService = mock(QuoteProductBomCostingBuildService.class);
    settlementRuleMatcher = mock(BomSettlementRuleMatcher.class);
    materialAttributeResolver = mock(BomRuleMaterialAttributeResolver.class);
    when(materialAttributeResolver.resolve(any(), any())).thenReturn(Map.of());
    versionInvalidationService = mock(QuoteCostRunVersionInvalidationService.class);
    quoteBomStatusService = mock(QuoteBomStatusService.class);
    service =
        new QuoteCostingWorkbenchServiceImpl(
            oaFormMapper,
            oaFormItemMapper,
            quoteBomStatusMapper,
            bomCostingRowMapper,
            bomCostingRowSubRefMapper,
            bomRawHierarchyMapper,
            materialMasterRawMapper,
            settlementRuleMapper,
            byproductCostRuleMapper,
            quoteBomConfirmationMapper,
            workbenchSummaryMapper,
            priceTypeConfirmBatchMapper,
            costingBuildService,
            settlementRuleMatcher,
            materialAttributeResolver,
            versionInvalidationService,
            new QuoteEffectiveBomFeatureSwitch(true),
            quoteBomStatusService);
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
        .containsExactly("DONE", "PENDING", "BLOCKED", "BLOCKED", "BLOCKED");
    assertThat(response.getWorkflowStatus().getCurrentBlockedStep())
        .isEqualTo("PRICE_TYPE_CONFIRMATION");
    verify(costingBuildService, never()).buildByOaFormItem(any());
  }

  @Test
  void rollupParentCarriesMatchedChildForCombinedDisplayNameWithoutAddingBomRows() {
    BomCostingRow parent = row(10L, "FIN-001", "201190083");
    parent.setMaterialName("接管");
    parent.setMaterialSpec("T-JG-0029");
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

    QuoteCostingWorkbenchResponse response = service.getWorkbench("OA-001", 10L);

    assertThat(response.getBomRows()).singleElement().satisfies(row -> {
      assertThat(row.getChildCode()).isEqualTo("201190083");
      assertThat(row.getChildName()).isEqualTo("接管");
      assertThat(row.getRollupComponents()).singleElement().satisfies(component -> {
        assertThat(component.getChildCode()).isEqualTo("301050120");
        assertThat(component.getChildName()).isEqualTo("拉制铜管");
        assertThat(component.getParentDrawingNo()).isEqualTo("T-JG-0029");
        assertThat(component.getUsageQty()).isEqualByComparingTo("0.00381546");
      });
    });
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
  void launchWorkbenchRebuildsExistingUnconfirmedSnapshotEvenWhenRulesAreNotNewer() {
    BomCostingRow existing = row(10L, "FIN-001", "MAT-1");
    existing.setBuiltAt(LocalDateTime.of(2026, 6, 30, 10, 0));
    BomCostingRow rebuilt = row(10L, "FIN-001", "MAT-NEW");
    rebuilt.setBuiltAt(LocalDateTime.of(2026, 6, 30, 11, 0));
    rebuilt.setBuildBatchId("forced_refresh_batch");
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item(10L, "FIN-001"));
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status(SAMPLE_PERIOD_MONTH));
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(List.of(existing))
        .thenReturn(List.of(rebuilt));
    when(settlementRuleMapper.selectLatestRuleChangeTime())
        .thenReturn(LocalDateTime.of(2026, 6, 30, 9, 0));
    when(byproductCostRuleMapper.selectLatestRuleChangeTime())
        .thenReturn(LocalDateTime.of(2026, 6, 30, 9, 30));
    when(costingBuildService.buildByOaFormItem(10L, SAMPLE_PERIOD_MONTH, LocalDate.now()))
        .thenReturn(buildResponse("forced_refresh_batch"));

    QuoteCostingWorkbenchResponse response = service.launchWorkbench("OA-001", 10L);

    assertThat(response.getSnapshotGenerated()).isTrue();
    assertThat(response.getBomRows()).hasSize(1);
    assertThat(response.getBomRows().get(0).getChildCode()).isEqualTo("MAT-NEW");
    verify(costingBuildService).buildByOaFormItem(10L, SAMPLE_PERIOD_MONTH, LocalDate.now());
    verify(quoteBomStatusService).checkItemForCostRun("OA-001", 10L);
    verify(quoteBomConfirmationMapper).update(any(), any());
    verify(priceTypeConfirmBatchMapper).update(any(), any());
    verify(versionInvalidationService)
        .invalidateProduct("OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH);
  }

  @Test
  void launchWorkbenchPreservesExistingSnapshotWhenBomIsConfirmed() {
    BomCostingRow existing = row(10L, "FIN-001", "MAT-1");
    existing.setBuiltAt(LocalDateTime.of(2026, 6, 30, 10, 0));
    QuoteBomConfirmation activeConfirmation = new QuoteBomConfirmation();
    activeConfirmation.setId(501L);
    activeConfirmation.setConfirmStatus(QuoteBomConfirmation.STATUS_CONFIRMED);
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item(10L, "FIN-001"));
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status(SAMPLE_PERIOD_MONTH));
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(List.of(existing));
    when(quoteBomConfirmationMapper.selectOne(any())).thenReturn(activeConfirmation);

    QuoteCostingWorkbenchResponse response = service.launchWorkbench("OA-001", 10L);

    assertThat(response.getSnapshotGenerated()).isFalse();
    assertThat(response.getBomRows()).extracting("childCode").containsExactly("MAT-1");
    verify(costingBuildService, never()).buildByOaFormItem(any());
    verify(quoteBomConfirmationMapper, never()).update(any(), any());
    verify(priceTypeConfirmBatchMapper, never()).update(any(), any());
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
        .thenReturn(List.of(oldRow))
        .thenReturn(List.of(rebuiltRow));
    when(materialMasterRawMapper.selectByLatestBatchAndCodes(
            any(), isNull(), eq("COMMERCIAL")))
        .thenReturn(List.of(commercialMaster));
    when(costingBuildService.buildByOaFormItem(10L, SAMPLE_PERIOD_MONTH, LocalDate.now()))
        .thenReturn(buildResponse("cross_org_batch"));

    QuoteCostingWorkbenchResponse response = service.launchWorkbench("OA-001", 10L);

    assertThat(response.getSnapshotGenerated()).isTrue();
    assertThat(response.getBomRows()).hasSize(1);
    QuoteCostingWorkbenchBomRowResponse row = response.getBomRows().get(0);
    assertThat(row.getChildCode()).isEqualTo("9990000050426");
    assertThat(row.getShapeAttribute()).isEqualTo("制造件");
    assertThat(row.getPriceOrgCode()).isEqualTo("210");
    assertThat(row.getMaterialOrganizationCode()).isEqualTo("COMMERCIAL");
    verify(costingBuildService).buildByOaFormItem(10L, SAMPLE_PERIOD_MONTH, LocalDate.now());
  }

  @Test
  void launchWorkbenchRebuildsWhenSettlementRulesAreNewerThanSnapshot() {
    BomCostingRow oldRow = row(10L, "FIN-001", "MAT-OLD");
    oldRow.setBuiltAt(LocalDateTime.of(2026, 6, 30, 9, 0));
    BomCostingRow newRow = row(10L, "FIN-001", "MAT-NEW");
    newRow.setBuiltAt(LocalDateTime.of(2026, 6, 30, 10, 0));
    newRow.setBuildBatchId("new_batch");
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item(10L, "FIN-001"));
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status(SAMPLE_PERIOD_MONTH));
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(List.of(oldRow))
        .thenReturn(List.of(newRow));
    when(settlementRuleMapper.selectLatestRuleChangeTime())
        .thenReturn(LocalDateTime.of(2026, 6, 30, 9, 30));
    when(byproductCostRuleMapper.selectLatestRuleChangeTime())
        .thenReturn(LocalDateTime.of(2026, 6, 30, 8, 0));
    when(costingBuildService.buildByOaFormItem(10L, SAMPLE_PERIOD_MONTH, LocalDate.now()))
        .thenReturn(
            new QuoteBomCostingBuildResponse(
                201L,
                null,
                10L,
                "OA-001",
                "FIN-001",
                "NON_BARE",
                SAMPLE_PERIOD_MONTH,
                "new_batch",
                1,
                1,
                0,
                Map.of("RAW_PRODUCT_BOM", 1),
                List.of(),
                LocalDateTime.of(2026, 6, 30, 10, 0)));

    QuoteCostingWorkbenchResponse response = service.launchWorkbench("OA-001", 10L);

    assertThat(response.getSnapshotGenerated()).isTrue();
    assertThat(response.getBuildBatchId()).isEqualTo("new_batch");
    assertThat(response.getBomRows()).hasSize(1);
    assertThat(response.getBomRows().get(0).getChildCode()).isEqualTo("MAT-NEW");
    verify(costingBuildService).buildByOaFormItem(10L, SAMPLE_PERIOD_MONTH, LocalDate.now());
    verify(quoteBomConfirmationMapper).update(any(), any());
    verify(priceTypeConfirmBatchMapper).update(any(), any());
  }

  @Test
  void launchWorkbenchRebuildsWhenSourceBomBuiltAfterSnapshot() {
    BomCostingRow oldRow = row(10L, "FIN-001", "MAT-OLD");
    oldRow.setBuiltAt(LocalDateTime.of(2026, 6, 30, 9, 0));
    oldRow.setRawHierarchyNodeId(102L);
    oldRow.setPriceOrgCode("210");
    oldRow.setBomPurpose("主制造");
    oldRow.setAsOfDate(LocalDate.of(2026, 7, 1));
    BomCostingRow newRow = row(10L, "FIN-001", "MAT-NEW");
    newRow.setBuiltAt(LocalDateTime.of(2026, 6, 30, 10, 0));
    newRow.setBuildBatchId("new_batch");
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item(10L, "FIN-001"));
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status(SAMPLE_PERIOD_MONTH));
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(List.of(oldRow))
        .thenReturn(List.of(newRow));
    when(settlementRuleMapper.selectLatestRuleChangeTime())
        .thenReturn(LocalDateTime.of(2026, 6, 30, 8, 0));
    when(byproductCostRuleMapper.selectLatestRuleChangeTime())
        .thenReturn(LocalDateTime.of(2026, 6, 30, 8, 0));
    when(bomRawHierarchyMapper.selectList(any()))
        .thenReturn(
            List.of(
                raw(101L, "FIN-001", "FIN-001", "/FIN-001/", 0, 0, "制造件", "10", "阀类",
                    LocalDateTime.of(2026, 6, 30, 10, 0)),
                raw(102L, "FIN-001", "MAT-OLD", "/FIN-001/MAT-OLD/", 1, 1, "采购件", "17",
                    "原材料", LocalDateTime.of(2026, 6, 30, 10, 0))));
    when(costingBuildService.buildByOaFormItem(10L, SAMPLE_PERIOD_MONTH, LocalDate.now()))
        .thenReturn(buildResponse("new_batch"));

    QuoteCostingWorkbenchResponse response = service.launchWorkbench("OA-001", 10L);

    assertThat(response.getSnapshotGenerated()).isTrue();
    assertThat(response.getBuildBatchId()).isEqualTo("new_batch");
    assertThat(response.getBomRows()).extracting("childCode").containsExactly("MAT-NEW");
    verify(costingBuildService).buildByOaFormItem(10L, SAMPLE_PERIOD_MONTH, LocalDate.now());
  }

  @Test
  void launchWorkbenchRebuildsWhenExistingRowsNowMatchExcludeRule() {
    BomCostingRow oldOilRow = row(10L, "FIN-001", "311020089");
    oldOilRow.setBuiltAt(LocalDateTime.of(2026, 6, 30, 10, 0));
    oldOilRow.setRawHierarchyNodeId(102L);
    oldOilRow.setPriceOrgCode("210");
    oldOilRow.setBomPurpose("主制造");
    oldOilRow.setAsOfDate(LocalDate.of(2026, 7, 1));
    BomCostingRow newRow = row(10L, "FIN-001", "MAT-NEW");
    newRow.setBuiltAt(LocalDateTime.of(2026, 6, 30, 11, 0));
    newRow.setBuildBatchId("new_batch");
    BomSettlementRule oilRule = excludeRule("AUXILIARY_EXCLUDE_OIL");
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item(10L, "FIN-001"));
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status(SAMPLE_PERIOD_MONTH));
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(List.of(oldOilRow))
        .thenReturn(List.of(newRow));
    when(settlementRuleMapper.selectLatestRuleChangeTime())
        .thenReturn(LocalDateTime.of(2026, 6, 30, 9, 0));
    when(byproductCostRuleMapper.selectLatestRuleChangeTime())
        .thenReturn(LocalDateTime.of(2026, 6, 30, 9, 0));
    when(settlementRuleMapper.selectList(any())).thenReturn(List.of(oilRule));
    when(bomRawHierarchyMapper.selectList(any()))
        .thenReturn(
            List.of(
                raw(101L, "FIN-001", "FIN-001", "/FIN-001/", 0, 0, "制造件", "10", "阀类",
                    LocalDateTime.of(2026, 6, 30, 9, 30)),
                raw(102L, "FIN-001", "311020089", "/FIN-001/311020089/", 1, 1, "采购件",
                    "181851454", "油类", LocalDateTime.of(2026, 6, 30, 9, 30))));
    when(settlementRuleMatcher.match(any(), any(), any(), any(), any(), any()))
        .thenReturn(Optional.of(oilRule));
    when(costingBuildService.buildByOaFormItem(10L, SAMPLE_PERIOD_MONTH, LocalDate.now()))
        .thenReturn(buildResponse("new_batch"));

    QuoteCostingWorkbenchResponse response = service.launchWorkbench("OA-001", 10L);

    assertThat(response.getSnapshotGenerated()).isTrue();
    assertThat(response.getBomRows()).extracting("childCode").containsExactly("MAT-NEW");
    verify(costingBuildService).buildByOaFormItem(10L, SAMPLE_PERIOD_MONTH, LocalDate.now());
  }

  @Test
  void bomConfirmedMakesQuoteBomDoneAndPriceTypePending() {
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item(10L, "FIN-001"));
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status(SAMPLE_PERIOD_MONTH));
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(List.of(row(10L, "FIN-001", "MAT-1")));
    when(workbenchSummaryMapper.selectLatestBomConfirmation("OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(bomConfirmation("CONFIRMED"));

    QuoteCostingWorkbenchResponse response = service.getWorkbench("OA-001", 10L);

    assertThat(response.getLatestBomConfirmation().getConfirmNo()).isEqualTo("BOM-CF-001");
    assertThat(response.getWorkflowStatus().getQuoteBomStatus()).isEqualTo("DONE");
    assertThat(response.getWorkflowStatus().getPriceTypeConfirmationStatus()).isEqualTo("PENDING");
    assertThat(response.getTabs()).extracting("code", "status")
        .contains(
            org.assertj.core.groups.Tuple.tuple("QUOTE_BOM", "DONE"),
            org.assertj.core.groups.Tuple.tuple("PRICE_TYPE_CONFIRMATION", "PENDING"));
  }

  @Test
  void priceTypeGapMakesPriceTypePartial() {
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item(10L, "FIN-001"));
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status(SAMPLE_PERIOD_MONTH));
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(List.of(row(10L, "FIN-001", "MAT-1")));
    when(workbenchSummaryMapper.selectLatestBomConfirmation("OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(bomConfirmation("CONFIRMED"));
    when(
            workbenchSummaryMapper.selectLatestPriceTypeConfirmation(
                "OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(priceTypeConfirmation("DRAFT", 2));

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
    when(workbenchSummaryMapper.selectLatestBomConfirmation("OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(bomConfirmation("CONFIRMED"));
    when(
            workbenchSummaryMapper.selectLatestPriceTypeConfirmation(
                "OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(priceTypeConfirmation("CONFIRMED", 0));
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
    when(workbenchSummaryMapper.selectLatestBomConfirmation("OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(bomConfirmation("CONFIRMED"));
    when(
            workbenchSummaryMapper.selectLatestPriceTypeConfirmation(
                "OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(priceTypeConfirmation("CONFIRMED", 0));
    when(workbenchSummaryMapper.selectLatestPricePrepare("OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(pricePrepare("PARTIAL", 1));
    when(workbenchSummaryMapper.selectLatestCostRun("OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(costRun("TRIAL"));

    QuoteCostingWorkbenchResponse response = service.getWorkbench("OA-001", 10L);

    assertThat(response.getWorkflowStatus().getPricePrepareStatus()).isEqualTo("PARTIAL");
    assertThat(response.getWorkflowStatus().getCostRunStatus()).isEqualTo("BLOCKED");
    assertThat(response.getWorkflowStatus().getOverallStatus()).isEqualTo("BLOCKED");
    assertThat(response.getWorkflowStatus().getCurrentBlockedStep()).isEqualTo("COST_RUN");
  }

  @Test
  void confirmedCostRunMakesCostRunDone() {
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item(10L, "FIN-001"));
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status(SAMPLE_PERIOD_MONTH));
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(List.of(row(10L, "FIN-001", "MAT-1")));
    when(workbenchSummaryMapper.selectLatestBomConfirmation("OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(bomConfirmation("CONFIRMED"));
    when(
            workbenchSummaryMapper.selectLatestPriceTypeConfirmation(
                "OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(priceTypeConfirmation("CONFIRMED", 0));
    when(workbenchSummaryMapper.selectLatestPricePrepare("OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(pricePrepare("SUCCESS", 0));
    when(workbenchSummaryMapper.selectLatestCostRun("OA-001", 10L, "FIN-001", SAMPLE_PERIOD_MONTH))
        .thenReturn(costRun("CONFIRMED"));

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
        .thenReturn(List.of())
        .thenReturn(List.of(row(10L, "FIN-001", "MAT-1")));
    when(costingBuildService.buildByOaFormItem(10L, SAMPLE_PERIOD_MONTH, LocalDate.now()))
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
    verify(costingBuildService).buildByOaFormItem(10L, SAMPLE_PERIOD_MONTH, LocalDate.now());
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

  private QuoteBomConfirmationSummaryResponse bomConfirmation(String status) {
    QuoteBomConfirmationSummaryResponse response = new QuoteBomConfirmationSummaryResponse();
    response.setId(701L);
    response.setConfirmNo("BOM-CF-001");
    response.setOaNo("OA-001");
    response.setOaFormItemId(10L);
    response.setTopProductCode("FIN-001");
    response.setPeriodMonth(SAMPLE_PERIOD_MONTH);
    response.setConfirmStatus(status);
    response.setRowCount(1);
    return response;
  }

  private QuoteBomConfirmation activeBomConfirmation() {
    QuoteBomConfirmation confirmation = new QuoteBomConfirmation();
    confirmation.setId(701L);
    confirmation.setConfirmNo("BOM-CF-001");
    confirmation.setOaNo("OA-001");
    confirmation.setOaFormItemId(10L);
    confirmation.setTopProductCode("FIN-001");
    confirmation.setPeriodMonth(SAMPLE_PERIOD_MONTH);
    confirmation.setConfirmStatus(QuoteBomConfirmation.STATUS_CONFIRMED);
    confirmation.setConfirmVersion(1);
    return confirmation;
  }

  private QuotePriceTypeConfirmationSummaryResponse priceTypeConfirmation(String status, int gaps) {
    QuotePriceTypeConfirmationSummaryResponse response = new QuotePriceTypeConfirmationSummaryResponse();
    response.setId(801L);
    response.setConfirmNo("PT-CF-001");
    response.setOaNo("OA-001");
    response.setOaFormItemId(10L);
    response.setProductCode("FIN-001");
    response.setPeriodMonth(SAMPLE_PERIOD_MONTH);
    response.setBomConfirmNo("BOM-CF-001");
    response.setStatus(status);
    response.setGapCount(gaps);
    return response;
  }

  private QuotePricePrepareSummaryResponse pricePrepare(String status, int gaps) {
    QuotePricePrepareSummaryResponse response = new QuotePricePrepareSummaryResponse();
    response.setId(901L);
    response.setPrepareNo("PP-001");
    response.setOaNo("OA-001");
    response.setOaFormItemId(10L);
    response.setTopProductCode("FIN-001");
    response.setPriceTypeConfirmNo("PT-CF-001");
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

  private BomRawHierarchy raw(
      Long id,
      String topProductCode,
      String materialCode,
      String path,
      int level,
      int isLeaf,
      String shapeAttr,
      String mainCategoryCode,
      String mainCategoryName,
      LocalDateTime builtAt) {
    BomRawHierarchy raw = new BomRawHierarchy();
    raw.setId(id);
    raw.setPriceOrgCode("210");
    raw.setTopProductCode(topProductCode);
    raw.setParentCode(level == 0 ? topProductCode : topProductCode);
    raw.setMaterialCode(materialCode);
    raw.setMaterialName(materialCode + " name");
    raw.setLevel(level);
    raw.setPath(path);
    raw.setSortSeq(level);
    raw.setQtyPerParent(BigDecimal.ONE);
    raw.setQtyPerTop(BigDecimal.ONE);
    raw.setShapeAttr(shapeAttr);
    raw.setSourceCategory(shapeAttr);
    raw.setMaterialCategory1(mainCategoryCode);
    raw.setMaterialCategory2(mainCategoryName);
    raw.setBomPurpose("主制造");
    raw.setIsLeaf(isLeaf);
    raw.setEffectiveFrom(LocalDate.of(2026, 1, 1));
    raw.setSourceType("U9");
    raw.setBuiltAt(builtAt);
    return raw;
  }

  private BomSettlementRule excludeRule(String ruleCode) {
    BomSettlementRule rule = new BomSettlementRule();
    rule.setId(401L);
    rule.setRuleCode(ruleCode);
    rule.setRuleName("辅料排除");
    rule.setRuleCategory("AUXILIARY_EXCLUDE");
    rule.setSettlementAction("EXCLUDE");
    rule.setSettlementRowType("EXCLUDED");
    rule.setPriority(40);
    rule.setEnabled(1);
    return rule;
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
