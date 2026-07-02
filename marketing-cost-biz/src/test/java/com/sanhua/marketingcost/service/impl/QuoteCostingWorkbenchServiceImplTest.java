package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingBuildResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBomConfirmationSummaryResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunSummaryResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostingBomRowUpdateRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostingWorkbenchBomRowResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostingWorkbenchResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareSummaryResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeConfirmationSummaryResponse;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteBomConfirmation;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import com.sanhua.marketingcost.entity.QuotePriceTypeConfirmBatch;
import com.sanhua.marketingcost.mapper.BomByproductCostRuleMapper;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.BomSettlementRuleMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomConfirmationMapper;
import com.sanhua.marketingcost.mapper.QuoteBomStatusMapper;
import com.sanhua.marketingcost.mapper.QuoteCostingWorkbenchSummaryMapper;
import com.sanhua.marketingcost.mapper.QuotePriceTypeConfirmBatchMapper;
import com.sanhua.marketingcost.service.QuoteProductBomCostingBuildService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QuoteCostingWorkbenchServiceImplTest {

  private static final String SAMPLE_OA_NO = "FI-SC-006-20260108-109";
  private static final Long SAMPLE_OA_FORM_ITEM_ID = 180L;
  private static final String SAMPLE_PRODUCT_CODE = "1001900001090";
  private static final String SAMPLE_PERIOD_MONTH = "2026-06";
  private static final String SAMPLE_BUILD_BATCH_ID = "f_20260609_12fe06";
  private static final BigDecimal SAMPLE_TOTAL_COST = new BigDecimal("137.807919");

  private OaFormMapper oaFormMapper;
  private OaFormItemMapper oaFormItemMapper;
  private QuoteBomStatusMapper quoteBomStatusMapper;
  private BomCostingRowMapper bomCostingRowMapper;
  private BomSettlementRuleMapper settlementRuleMapper;
  private BomByproductCostRuleMapper byproductCostRuleMapper;
  private QuoteBomConfirmationMapper quoteBomConfirmationMapper;
  private QuoteCostingWorkbenchSummaryMapper workbenchSummaryMapper;
  private QuotePriceTypeConfirmBatchMapper priceTypeConfirmBatchMapper;
  private QuoteProductBomCostingBuildService costingBuildService;
  private QuoteCostingWorkbenchServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, OaForm.class);
    TableInfoHelper.initTableInfo(assistant, OaFormItem.class);
    TableInfoHelper.initTableInfo(assistant, QuoteBomStatus.class);
    TableInfoHelper.initTableInfo(assistant, BomCostingRow.class);
    TableInfoHelper.initTableInfo(assistant, QuoteBomConfirmation.class);
    TableInfoHelper.initTableInfo(assistant, QuotePriceTypeConfirmBatch.class);
  }

  @BeforeEach
  void setUp() {
    oaFormMapper = mock(OaFormMapper.class);
    oaFormItemMapper = mock(OaFormItemMapper.class);
    quoteBomStatusMapper = mock(QuoteBomStatusMapper.class);
    bomCostingRowMapper = mock(BomCostingRowMapper.class);
    settlementRuleMapper = mock(BomSettlementRuleMapper.class);
    byproductCostRuleMapper = mock(BomByproductCostRuleMapper.class);
    quoteBomConfirmationMapper = mock(QuoteBomConfirmationMapper.class);
    workbenchSummaryMapper = mock(QuoteCostingWorkbenchSummaryMapper.class);
    priceTypeConfirmBatchMapper = mock(QuotePriceTypeConfirmBatchMapper.class);
    costingBuildService = mock(QuoteProductBomCostingBuildService.class);
    service =
        new QuoteCostingWorkbenchServiceImpl(
            oaFormMapper,
            oaFormItemMapper,
            quoteBomStatusMapper,
            bomCostingRowMapper,
            settlementRuleMapper,
            byproductCostRuleMapper,
            quoteBomConfirmationMapper,
            workbenchSummaryMapper,
            priceTypeConfirmBatchMapper,
            costingBuildService);
  }

  @Test
  void existingSnapshotDoesNotBuildAgain() {
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item(10L, "FIN-001"));
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status("2026-06"));
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(List.of(row(10L, "FIN-001", "MAT-1")));

    QuoteCostingWorkbenchResponse response = service.getWorkbench("OA-001", 10L);

    assertThat(response.getSnapshotGenerated()).isFalse();
    assertThat(response.getPeriodMonth()).isEqualTo("2026-06");
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
  void launchWorkbenchReusesExistingSnapshotWhenRulesAreNotNewer() {
    BomCostingRow existing = row(10L, "FIN-001", "MAT-1");
    existing.setBuiltAt(LocalDateTime.of(2026, 6, 30, 10, 0));
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item(10L, "FIN-001"));
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status("2026-06"));
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(List.of(existing));
    when(settlementRuleMapper.selectLatestRuleChangeTime())
        .thenReturn(LocalDateTime.of(2026, 6, 30, 9, 0));
    when(byproductCostRuleMapper.selectLatestRuleChangeTime())
        .thenReturn(LocalDateTime.of(2026, 6, 30, 9, 30));

    QuoteCostingWorkbenchResponse response = service.launchWorkbench("OA-001", 10L);

    assertThat(response.getSnapshotGenerated()).isFalse();
    assertThat(response.getBomRows()).hasSize(1);
    assertThat(response.getBomRows().get(0).getChildCode()).isEqualTo("MAT-1");
    verify(costingBuildService, never()).buildByOaFormItem(any());
    verify(quoteBomConfirmationMapper, never()).update(any(), any());
    verify(priceTypeConfirmBatchMapper, never()).update(any(), any());
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
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status("2026-06"));
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(List.of(oldRow))
        .thenReturn(List.of(newRow));
    when(settlementRuleMapper.selectLatestRuleChangeTime())
        .thenReturn(LocalDateTime.of(2026, 6, 30, 9, 30));
    when(byproductCostRuleMapper.selectLatestRuleChangeTime())
        .thenReturn(LocalDateTime.of(2026, 6, 30, 8, 0));
    when(costingBuildService.buildByOaFormItem(10L, "2026-06", LocalDate.now()))
        .thenReturn(
            new QuoteBomCostingBuildResponse(
                201L,
                null,
                10L,
                "OA-001",
                "FIN-001",
                "NON_BARE",
                "2026-06",
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
    verify(costingBuildService).buildByOaFormItem(10L, "2026-06", LocalDate.now());
    verify(quoteBomConfirmationMapper).update(any(), any());
    verify(priceTypeConfirmBatchMapper).update(any(), any());
  }

  @Test
  void bomConfirmedMakesQuoteBomDoneAndPriceTypePending() {
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item(10L, "FIN-001"));
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status("2026-06"));
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(List.of(row(10L, "FIN-001", "MAT-1")));
    when(workbenchSummaryMapper.selectLatestBomConfirmation("OA-001", 10L, "FIN-001", "2026-06"))
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
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status("2026-06"));
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(List.of(row(10L, "FIN-001", "MAT-1")));
    when(workbenchSummaryMapper.selectLatestBomConfirmation("OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(bomConfirmation("CONFIRMED"));
    when(
            workbenchSummaryMapper.selectLatestPriceTypeConfirmation(
                "OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(priceTypeConfirmation("DRAFT", 2));

    QuoteCostingWorkbenchResponse response = service.getWorkbench("OA-001", 10L);

    assertThat(response.getWorkflowStatus().getPriceTypeConfirmationStatus()).isEqualTo("PARTIAL");
    assertThat(response.getWorkflowStatus().getPricePrepareStatus()).isEqualTo("BLOCKED");
  }

  @Test
  void pricePrepareGapMakesPreparePartial() {
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item(10L, "FIN-001"));
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status("2026-06"));
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(List.of(row(10L, "FIN-001", "MAT-1")));
    when(workbenchSummaryMapper.selectLatestBomConfirmation("OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(bomConfirmation("CONFIRMED"));
    when(
            workbenchSummaryMapper.selectLatestPriceTypeConfirmation(
                "OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(priceTypeConfirmation("CONFIRMED", 0));
    when(workbenchSummaryMapper.selectLatestPricePrepare("OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(pricePrepare("PARTIAL", 1));

    QuoteCostingWorkbenchResponse response = service.getWorkbench("OA-001", 10L);

    assertThat(response.getWorkflowStatus().getPricePrepareStatus()).isEqualTo("PARTIAL");
    assertThat(response.getWorkflowStatus().getCostRunStatus()).isEqualTo("BLOCKED");
  }

  @Test
  void trialCostRunStaysBlockedWhenPrepareStillHasWarnings() {
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item(10L, "FIN-001"));
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status("2026-06"));
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(List.of(row(10L, "FIN-001", "MAT-1")));
    when(workbenchSummaryMapper.selectLatestBomConfirmation("OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(bomConfirmation("CONFIRMED"));
    when(
            workbenchSummaryMapper.selectLatestPriceTypeConfirmation(
                "OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(priceTypeConfirmation("CONFIRMED", 0));
    when(workbenchSummaryMapper.selectLatestPricePrepare("OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(pricePrepare("PARTIAL", 1));
    when(workbenchSummaryMapper.selectLatestCostRun("OA-001", 10L, "FIN-001", "2026-06"))
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
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status("2026-06"));
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(List.of(row(10L, "FIN-001", "MAT-1")));
    when(workbenchSummaryMapper.selectLatestBomConfirmation("OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(bomConfirmation("CONFIRMED"));
    when(
            workbenchSummaryMapper.selectLatestPriceTypeConfirmation(
                "OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(priceTypeConfirmation("CONFIRMED", 0));
    when(workbenchSummaryMapper.selectLatestPricePrepare("OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(pricePrepare("SUCCESS", 0));
    when(workbenchSummaryMapper.selectLatestCostRun("OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(costRun("CONFIRMED"));

    QuoteCostingWorkbenchResponse response = service.getWorkbench("OA-001", 10L);

    assertThat(response.getLatestCostRun().getVersionNo()).isEqualTo("COST-001-V1");
    assertThat(response.getWorkflowStatus().getCostRunStatus()).isEqualTo("DONE");
    assertThat(response.getWorkflowStatus().getOverallStatus()).isEqualTo("DONE");
  }

  @Test
  void missingSnapshotBuildsThenReturnsRows() {
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item(10L, "FIN-001"));
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status("2026-06"));
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(List.of())
        .thenReturn(List.of(row(10L, "FIN-001", "MAT-1")));
    when(costingBuildService.buildByOaFormItem(10L, "2026-06", LocalDate.now()))
        .thenReturn(
            new QuoteBomCostingBuildResponse(
                201L,
                null,
                10L,
                "OA-001",
                "FIN-001",
                "NON_BARE",
                "2026-06",
                "qbp_20260608_abcd1234",
                1,
                1,
                0,
                Map.of("RAW_PRODUCT_BOM", 1),
                List.of(),
                LocalDateTime.now()));

    QuoteCostingWorkbenchResponse response = service.getWorkbench("OA-001", 10L);

    assertThat(response.getSnapshotGenerated()).isTrue();
    assertThat(response.getBuildBatchId()).isEqualTo("qbp_20260608_abcd1234");
    assertThat(response.getBomRows()).extracting("childCode").containsExactly("MAT-1");
    verify(costingBuildService).buildByOaFormItem(10L, "2026-06", LocalDate.now());
  }

  @Test
  void snapshotQueryUsesQuoteItemProductAndPeriodScope() {
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(20L)).thenReturn(item(20L, "FIN-002"));
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status("2026-06"));
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 20L, "FIN-002", "2026-06"))
        .thenReturn(List.of(row(20L, "FIN-002", "MAT-2")));

    service.getWorkbench("OA-001", 20L);

    verify(bomCostingRowMapper)
        .selectQuoteCostingSnapshot("OA-001", 20L, "FIN-002", "2026-06");
  }

  @Test
  void goldenSampleWorkbenchUsesProductLineScopeBeforeWorkbenchRemodel() {
    // QWB-00 基线：真实库当前成本结果为 137.807919，工作台必须按产品行读取成本版本摘要。
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

  @Test
  void updateBomRowSavesAllowedFieldsAndAuditOnly() {
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item(10L, "FIN-001"));
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status("2026-06"));
    when(bomCostingRowMapper.selectById(300L)).thenReturn(row(10L, "FIN-001", "MAT-OLD"));
    when(bomCostingRowMapper.updateById(any(BomCostingRow.class))).thenReturn(1);
    QuoteCostingBomRowUpdateRequest request = updateRequest();

    QuoteCostingWorkbenchBomRowResponse response =
        service.updateBomRow("OA-001", 10L, 300L, request);

    assertThat(response.getChildCode()).isEqualTo("MAT-NEW");
    assertThat(response.getChildName()).isEqualTo("新子件");
    assertThat(response.getChildModel()).isEqualTo("NEW-SPEC");
    assertThat(response.getUsageQty()).isEqualByComparingTo("2.5");
    assertThat(response.getManualModified()).isEqualTo(1);
    assertThat(response.getModifiedBy()).isEqualTo("system");
    assertThat(response.getModifiedAt()).isNotNull();

    ArgumentCaptor<BomCostingRow> captor = ArgumentCaptor.forClass(BomCostingRow.class);
    verify(bomCostingRowMapper).updateById(captor.capture());
    BomCostingRow patch = captor.getValue();
    assertThat(patch.getId()).isEqualTo(300L);
    assertThat(patch.getMaterialCode()).isEqualTo("MAT-NEW");
    assertThat(patch.getMaterialName()).isEqualTo("新子件");
    assertThat(patch.getMaterialSpec()).isEqualTo("NEW-SPEC");
    assertThat(patch.getQtyPerParent()).isEqualByComparingTo("2.5");
    assertThat(patch.getUnit()).isEqualTo("PCS");
    assertThat(patch.getMaterialAttribute()).isEqualTo("铜");
    assertThat(patch.getShapeAttr()).isEqualTo("采购件");
    assertThat(patch.getManualModified()).isEqualTo(1);
    assertThat(patch.getModifiedBy()).isEqualTo("system");
    assertThat(patch.getModifiedAt()).isNotNull();
    assertThat(patch.getOaNo()).isNull();
    assertThat(patch.getOaFormItemId()).isNull();
    assertThat(patch.getTopProductCode()).isNull();
    assertThat(patch.getParentCode()).isNull();
    assertThat(patch.getPath()).isNull();
    assertThat(patch.getPeriodMonth()).isNull();
    assertThat(patch.getQtyPerTop()).isNull();
  }

  @Test
  void updateBomRowRejectsCrossOaRow() {
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item(10L, "FIN-001"));
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status("2026-06"));
    BomCostingRow row = row(10L, "FIN-001", "MAT-1");
    row.setOaNo("OA-OTHER");
    when(bomCostingRowMapper.selectById(300L)).thenReturn(row);

    assertThatThrownBy(() -> service.updateBomRow("OA-001", 10L, 300L, updateRequest()))
        .isInstanceOf(QuoteIngestException.class)
        .hasMessageContaining("当前报价单");
    verify(bomCostingRowMapper, never()).updateById(any(BomCostingRow.class));
  }

  @Test
  void updateBomRowRejectsCrossQuoteItemRow() {
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item(10L, "FIN-001"));
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status("2026-06"));
    BomCostingRow row = row(99L, "FIN-001", "MAT-1");
    when(bomCostingRowMapper.selectById(300L)).thenReturn(row);

    assertThatThrownBy(() -> service.updateBomRow("OA-001", 10L, 300L, updateRequest()))
        .isInstanceOf(QuoteIngestException.class)
        .hasMessageContaining("当前产品行");
    verify(bomCostingRowMapper, never()).updateById(any(BomCostingRow.class));
  }

  @Test
  void updateBomRowRejectsCrossPeriodRow() {
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item(10L, "FIN-001"));
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status("2026-06"));
    BomCostingRow row = row(10L, "FIN-001", "MAT-1");
    row.setPeriodMonth("2026-05");
    when(bomCostingRowMapper.selectById(300L)).thenReturn(row);

    assertThatThrownBy(() -> service.updateBomRow("OA-001", 10L, 300L, updateRequest()))
        .isInstanceOf(QuoteIngestException.class)
        .hasMessageContaining("当前核算月份");
    verify(bomCostingRowMapper, never()).updateById(any(BomCostingRow.class));
  }

  @Test
  void updateBomRowRejectsWhenBomAlreadyConfirmed() {
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item(10L, "FIN-001"));
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status("2026-06"));
    when(bomCostingRowMapper.selectById(300L)).thenReturn(row(10L, "FIN-001", "MAT-1"));
    when(quoteBomConfirmationMapper.selectOne(any())).thenReturn(activeBomConfirmation());

    assertThatThrownBy(() -> service.updateBomRow("OA-001", 10L, 300L, updateRequest()))
        .isInstanceOf(QuoteIngestException.class)
        .hasMessageContaining("先撤销确认");
    verify(bomCostingRowMapper, never()).updateById(any(BomCostingRow.class));
  }

  @Test
  void updateBomRowAllowsAfterBomConfirmationCancelled() {
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item(10L, "FIN-001"));
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status("2026-06"));
    when(bomCostingRowMapper.selectById(300L)).thenReturn(row(10L, "FIN-001", "MAT-OLD"));
    when(quoteBomConfirmationMapper.selectOne(any())).thenReturn(null);
    when(bomCostingRowMapper.updateById(any(BomCostingRow.class))).thenReturn(1);

    QuoteCostingWorkbenchBomRowResponse response =
        service.updateBomRow("OA-001", 10L, 300L, updateRequest());

    assertThat(response.getChildCode()).isEqualTo("MAT-NEW");
    verify(bomCostingRowMapper).updateById(any(BomCostingRow.class));
  }

  private OaForm form() {
    OaForm form = new OaForm();
    form.setId(1L);
    form.setOaNo("OA-001");
    form.setAccountingPeriodMonth("2026-06");
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
    response.setPeriodMonth("2026-06");
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
    confirmation.setPeriodMonth("2026-06");
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
    response.setPeriodMonth("2026-06");
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
    response.setPeriodMonth("2026-06");
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
    response.setResultPeriod("2026-06");
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
    row.setPeriodMonth("2026-06");
    row.setBuildBatchId("existing_batch");
    row.setPath("/" + topProductCode + "/" + materialCode + "/");
    row.setManualModified(0);
    return row;
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

  private QuoteCostingBomRowUpdateRequest updateRequest() {
    QuoteCostingBomRowUpdateRequest request = new QuoteCostingBomRowUpdateRequest();
    request.setChildCode("MAT-NEW");
    request.setChildName("新子件");
    request.setChildModel("NEW-SPEC");
    request.setUsageQty(new BigDecimal("2.5"));
    request.setUnit("PCS");
    request.setMaterialAttribute("铜");
    request.setShapeAttribute("采购件");
    return request;
  }
}
