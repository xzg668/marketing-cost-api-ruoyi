package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.dto.CostRunObjectResult;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.financequote.QuoteCuAdjustmentCalcResult;
import com.sanhua.marketingcost.dto.PriceRangeItemImportRequest;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.dto.priceprepare.NormalMaterialPricePrepareResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePreparePlanItem;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareReadinessResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingBuildResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunTrialRequest;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.CostRunCostItem;
import com.sanhua.marketingcost.entity.CostRunPartItem;
import com.sanhua.marketingcost.entity.CostRunTask;
import com.sanhua.marketingcost.entity.CostRunTraceSnapshot;
import com.sanhua.marketingcost.entity.MaterialMaster;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.entity.PriceRangeFactorRule;
import com.sanhua.marketingcost.entity.PriceRangeItem;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.entity.QuoteCostingWorkspace;
import com.sanhua.marketingcost.enums.MaterialFormAttrEnum;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.CostRunCostItemMapper;
import com.sanhua.marketingcost.mapper.CostRunPartItemMapper;
import com.sanhua.marketingcost.mapper.CostRunTaskMapper;
import com.sanhua.marketingcost.mapper.CostRunTraceSnapshotMapper;
import com.sanhua.marketingcost.mapper.MakePartPriceCalcRowMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.PriceFixedItemMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedCalcItemMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import com.sanhua.marketingcost.mapper.PricePrepareItemMapper;
import com.sanhua.marketingcost.mapper.PriceRangeFactorRuleMapper;
import com.sanhua.marketingcost.mapper.PriceRangeItemMapper;
import com.sanhua.marketingcost.mapper.QuoteCostRunVersionMapper;
import com.sanhua.marketingcost.mapper.QuoteCuMaterialDiffItemMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.CostRunCostItemService;
import com.sanhua.marketingcost.service.CostRunEngine;
import com.sanhua.marketingcost.service.CostRunPartItemService;
import com.sanhua.marketingcost.service.CostRunResultWriter;
import com.sanhua.marketingcost.service.CostRunResultService;
import com.sanhua.marketingcost.service.LinkedPriceEnsureService;
import com.sanhua.marketingcost.service.MaterialPriceRouterService;
import com.sanhua.marketingcost.service.PricePrepareReadinessService;
import com.sanhua.marketingcost.service.PricePrepareService;
import com.sanhua.marketingcost.service.QuoteCuAdjustmentCalcService;
import com.sanhua.marketingcost.service.QuoteCostRunVersionNoGenerator;
import com.sanhua.marketingcost.service.QuoteCostRunVersionService;
import com.sanhua.marketingcost.service.QuoteCostingWorkspaceService;
import com.sanhua.marketingcost.service.QuoteProductBomCostingBuildService;
import com.sanhua.marketingcost.service.pricing.RangePriceResolver;
import com.sanhua.marketingcost.service.pricing.RangePriceResolverTestSupport;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@DisplayName("MFRP-08 排水泵行情区间价端到端样例")
class MarketFactorRangePriceEndToEndSampleTest {

  private static final String OA_NO = "FI-SC-006-20260326-032";
  private static final Long OA_FORM_ID = 260326032L;
  private static final Long OA_FORM_ITEM_ID = 26032603201L;
  private static final String PRODUCT_CODE = "DRAIN-PUMP-6.25";
  private static final String BUSINESS_UNIT_TYPE = "COMMERCIAL";
  private static final String PERIOD_MONTH = CostPricingPeriodUtils.currentPricingMonth();
  private static final List<String> SAMPLE_PART_CODES = List.of("201850160", "201850162");
  private static final BigDecimal QUOTE_COPPER_PRICE = new BigDecimal("90000");
  private static final BigDecimal RANGE_LOW = new BigDecimal("87501");
  private static final BigDecimal RANGE_HIGH = new BigDecimal("92500");
  private static final BigDecimal HIT_UNIT_PRICE = new BigDecimal("0.3920353982300885");
  private static final BigDecimal BOM_QTY = new BigDecimal("0.655");
  private static final BigDecimal EXPECTED_AMOUNT = new BigDecimal("0.256783185840708");
  private static final BigDecimal AMOUNT_TOLERANCE = new BigDecimal("0.000000000000001");

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, PriceRangeItem.class);
    TableInfoHelper.initTableInfo(assistant, PriceRangeFactorRule.class);
    TableInfoHelper.initTableInfo(assistant, OaForm.class);
    TableInfoHelper.initTableInfo(assistant, OaFormItem.class);
    TableInfoHelper.initTableInfo(assistant, QuoteCostRunVersion.class);
    TableInfoHelper.initTableInfo(assistant, PricePrepareItem.class);
    TableInfoHelper.initTableInfo(assistant, BomCostingRow.class);
    TableInfoHelper.initTableInfo(assistant, MaterialMaster.class);
    TableInfoHelper.initTableInfo(assistant, CostRunPartItem.class);
    TableInfoHelper.initTableInfo(assistant, CostRunCostItem.class);
    TableInfoHelper.initTableInfo(assistant, CostRunTraceSnapshot.class);
    TableInfoHelper.initTableInfo(assistant, CostRunTask.class);
  }

  @BeforeEach
  void authenticateBusinessUnit() {
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken("mfrp.test", null, List.of());
    authentication.setDetails(
        Map.of(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, BUSINESS_UNIT_TYPE));
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  @AfterEach
  void clearAuthentication() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("排水泵样例：导入区间铜价后工作台试算和底稿均命中 90000 铜价区间")
  void drainPumpSampleRunsThroughQuotePrepareCostRunAndTrace() {
    PriceRangeItemMapper rangeItemMapper = mock(PriceRangeItemMapper.class);
    PriceRangeFactorRuleMapper factorRuleMapper = mock(PriceRangeFactorRuleMapper.class);
    OaFormMapper oaFormMapper = mock(OaFormMapper.class);
    MaterialPriceRouterService routerService = mock(MaterialPriceRouterService.class);
    LinkedPriceEnsureService linkedPriceEnsureService = mock(LinkedPriceEnsureService.class);
    PricePrepareItemMapper prepareItemMapper = mock(PricePrepareItemMapper.class);
    BomCostingRowMapper bomCostingRowMapper = mock(BomCostingRowMapper.class);
    MaterialMasterMapper materialMasterMapper = mock(MaterialMasterMapper.class);
    CostRunPartItemService partItemService = mock(CostRunPartItemService.class);
    CostRunCostItemService costItemService = mock(CostRunCostItemService.class);

    List<PriceRangeFactorRule> importedRules = new ArrayList<>();
    List<PriceRangeItem> importedItems = new ArrayList<>();
    stubRangeStorage(rangeItemMapper, factorRuleMapper, importedRules, importedItems);

    OaForm oaForm = sampleOaForm();
    when(oaFormMapper.selectOne(any(Wrapper.class))).thenReturn(oaForm);

    PriceRangeItemServiceImpl rangeImportService =
        new PriceRangeItemServiceImpl(
            rangeItemMapper,
            factorRuleMapper,
            mock(com.sanhua.marketingcost.service.MaterialPriceTypeRouteSyncService.class));
    List<PriceRangeItem> imported = rangeImportService.importItems(factorRangeImportRequest());

    assertThat(oaForm.getCopperPrice()).isEqualByComparingTo(QUOTE_COPPER_PRICE);
    assertThat(imported).hasSize(2);
    assertThat(importedRules)
        .extracting(PriceRangeFactorRule::getMaterialCode)
        .containsExactlyElementsOf(SAMPLE_PART_CODES);
    assertThat(importedRules)
        .allSatisfy(rule -> {
          assertThat(rule.getFactorCode()).isEqualTo("CU");
          assertThat(rule.getCurrentFlag()).isOne();
        });
    assertThat(importedItems)
        .allSatisfy(item -> {
          assertThat(item.getRangeBasis()).isEqualTo("FACTOR");
          assertThat(item.getFactorCode()).isEqualTo("CU");
          assertThat(item.getRangeLow()).isEqualByComparingTo(RANGE_LOW);
          assertThat(item.getRangeHigh()).isEqualByComparingTo(RANGE_HIGH);
          assertThat(item.getPriceExclTax()).isEqualByComparingTo(HIT_UNIT_PRICE);
        });

    RangePriceResolver rangeResolver =
        RangePriceResolverTestSupport.create(rangeItemMapper, factorRuleMapper, oaFormMapper);
    NormalMaterialPricePrepareStrategyImpl prepareStrategy =
        new NormalMaterialPricePrepareStrategyImpl(
            routerService, linkedPriceEnsureService, List.of(rangeResolver));
    SAMPLE_PART_CODES.forEach(code ->
        when(routerService.listCandidates(eq(code), eq(PERIOD_MONTH), any(LocalDate.class)))
            .thenReturn(List.of(rangeRoute(code))));

    List<PricePrepareItem> preparedItems = new ArrayList<>();
    Map<String, BomCostingRow> bomRows =
        SAMPLE_PART_CODES.stream().collect(Collectors.toMap(code -> code, this::bomRow));
    long prepareItemId = 12001L;
    for (String code : SAMPLE_PART_CODES) {
      NormalMaterialPricePrepareResult prepareResult =
          prepareStrategy.prepare(
              OA_NO,
              BUSINESS_UNIT_TYPE,
              PERIOD_MONTH,
              planItem(bomRows.get(code)));
      assertReadyRangePrice(code, prepareResult);
      preparedItems.add(toPrepareItem(prepareItemId++, bomRows.get(code), prepareResult));
    }

    stubPreparedProviderStorage(prepareItemMapper, bomCostingRowMapper, materialMasterMapper,
        preparedItems, bomRows);
    QuotePricePreparePartItemProviderImpl preparedProvider =
        new QuotePricePreparePartItemProviderImpl(
            prepareItemMapper, bomCostingRowMapper, materialMasterMapper);
    CostRunObjectCalcServiceImpl objectCalcService =
        new CostRunObjectCalcServiceImpl(
            partItemService,
            costItemService,
            List.of(preparedProvider));
    when(costItemService.listByMaterialCodes(
            eq(OA_NO),
            eq(PRODUCT_CODE),
            eq(Set.of(PRODUCT_CODE)),
            any(CostRunContext.class),
            any(),
            eq(false),
            any()))
        .thenReturn(List.of(totalCostItem(EXPECTED_AMOUNT.multiply(new BigDecimal("2")))));

    CostRunEngine costRunEngine = objectCalcService::calculate;
    QuoteCostRunWorkbenchServiceImpl workbench =
        quoteCostRunWorkbench(oaFormMapper, costRunEngine);

    QuoteCostRunTrialRequest trialRequest = new QuoteCostRunTrialRequest();
    trialRequest.setPeriodMonth(PERIOD_MONTH);
    trialRequest.setPricePrepareNo("PPR-MFRP-08");
    var response = workbench.runToSuccess(OA_NO, OA_FORM_ITEM_ID, trialRequest, "range-e2e");

    assertThat(response.getCurrentDisplayVersion().getStatus()).isEqualTo("SUCCESS");
    assertThat(response.getCurrentDisplayVersion().getPricePrepareNo()).isEqualTo("PPR-MFRP-08");
    assertThat(response.getResultHeader().getTotalCost())
        .isEqualByComparingTo(EXPECTED_AMOUNT.multiply(new BigDecimal("2")));
    assertThat(response.getPartItems())
        .extracting(CostRunPartItemDto::getPartCode)
        .containsExactlyElementsOf(SAMPLE_PART_CODES);
    response.getPartItems().forEach(part -> {
      assertThat(part.getPriceSource()).isEqualTo("区间价");
      assertThat(part.getUnitPrice()).isEqualByComparingTo(HIT_UNIT_PRICE);
      assertAmountWithinTolerance(part.getAmount());
    });
    assertThat(response.getBlockingReasons()).isEmpty();

    List<CostRunTraceSnapshot> snapshots =
        buildTraceSnapshots(preparedItems, importedItems, response.getPartItems());

    assertThat(snapshots).hasSize(2);
    snapshots.forEach(snapshot -> {
      assertThat(snapshot.getSourceType()).isEqualTo("RANGE_PRICE");
      assertAmountWithinTolerance(snapshot.getAmount());
      assertThat(snapshot.getSourceSnapshotJson())
          .contains(
              "\"factor_code\":\"CU\"",
              "\"factor_value\":90000",
              "\"range_low\":87501",
              "\"range_high\":92500",
              "\"matchedUnitPrice\":0.3920353982300885");
      assertThat(snapshot.getFormulaSnapshotJson())
          .contains("命中单价 × BOM 用量", "0.655");
      assertThat(snapshot.getStepsJson())
          .contains("FACTOR_RANGE_PRICE_ROW", "按报价单铜价命中区间");
    });
  }

  private void stubRangeStorage(
      PriceRangeItemMapper itemMapper,
      PriceRangeFactorRuleMapper factorRuleMapper,
      List<PriceRangeFactorRule> rules,
      List<PriceRangeItem> items) {
    when(factorRuleMapper.selectList(any(Wrapper.class))).thenAnswer(invocation -> {
      Wrapper<?> wrapper = invocation.getArgument(0);
      List<Object> values = paramValues(wrapper);
      return rules.stream()
          .filter(rule -> Integer.valueOf(1).equals(rule.getCurrentFlag()))
          .filter(rule -> values.contains(rule.getMaterialCode()))
          .toList();
    });
    when(factorRuleMapper.insert(any(PriceRangeFactorRule.class))).thenAnswer(invocation -> {
      PriceRangeFactorRule rule = invocation.getArgument(0);
      rule.setId(8100L + rules.size() + 1);
      rules.add(rule);
      return 1;
    });
    when(itemMapper.insert(any(PriceRangeItem.class))).thenAnswer(invocation -> {
      PriceRangeItem item = invocation.getArgument(0);
      item.setId(9100L + items.size() + 1);
      items.add(item);
      return 1;
    });
    when(itemMapper.selectList(any(Wrapper.class))).thenAnswer(invocation -> {
      Wrapper<?> wrapper = invocation.getArgument(0);
      List<Object> values = paramValues(wrapper);
      return items.stream()
          .filter(item -> values.contains(item.getFactorRuleId()) || values.contains(item.getMaterialCode()))
          .filter(item -> item.getCurrentFlag() == null || item.getCurrentFlag() == 1)
          .filter(item -> item.getRangeLow().compareTo(QUOTE_COPPER_PRICE) <= 0)
          .filter(item -> item.getRangeHigh().compareTo(QUOTE_COPPER_PRICE) >= 0)
          .sorted(Comparator.comparing(PriceRangeItem::getId).reversed())
          .toList();
    });
    when(itemMapper.selectById(anyLong())).thenAnswer(invocation -> {
      Long id = invocation.getArgument(0);
      return items.stream().filter(item -> id.equals(item.getId())).findFirst().orElse(null);
    });
  }

  private void stubPreparedProviderStorage(
      PricePrepareItemMapper prepareItemMapper,
      BomCostingRowMapper bomCostingRowMapper,
      MaterialMasterMapper materialMasterMapper,
      List<PricePrepareItem> preparedItems,
      Map<String, BomCostingRow> bomRows) {
    when(prepareItemMapper.selectList(any(Wrapper.class))).thenReturn(preparedItems);
    when(bomCostingRowMapper.selectList(any(Wrapper.class)))
        .thenReturn(new ArrayList<>(bomRows.values()));
    when(materialMasterMapper.selectList(any(Wrapper.class)))
        .thenReturn(SAMPLE_PART_CODES.stream().map(this::materialMaster).toList());
  }

  private QuoteCostRunWorkbenchServiceImpl quoteCostRunWorkbench(
      OaFormMapper oaFormMapper,
      CostRunEngine costRunEngine) {
    OaFormItemMapper oaFormItemMapper = mock(OaFormItemMapper.class);
    QuoteCostRunVersionMapper versionMapper = mock(QuoteCostRunVersionMapper.class);
    CostRunResultService resultService = mock(CostRunResultService.class);
    CostRunPartItemMapper partItemMapper = mock(CostRunPartItemMapper.class);
    CostRunCostItemMapper costItemMapper = mock(CostRunCostItemMapper.class);
    when(partItemMapper.selectCount(any(Wrapper.class))).thenReturn(2L);
    when(costItemMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
    CostRunTraceSnapshotMapper traceSnapshotMapper = mock(CostRunTraceSnapshotMapper.class);
    CostRunTaskMapper taskMapper = mock(CostRunTaskMapper.class);
    QuoteProductBomCostingBuildService costingBuildService =
        mock(QuoteProductBomCostingBuildService.class);
    PricePrepareService pricePrepareService = mock(PricePrepareService.class);
    PricePrepareReadinessService readinessService = mock(PricePrepareReadinessService.class);
    QuoteCostRunVersionService versionService = mock(QuoteCostRunVersionService.class);
    QuoteCostRunVersionNoGenerator versionNoGenerator =
        mock(QuoteCostRunVersionNoGenerator.class);
    CostRunResultWriter resultWriter = mock(CostRunResultWriter.class);

    OaFormItem oaFormItem = sampleOaFormItem();
    when(oaFormItemMapper.selectById(OA_FORM_ITEM_ID)).thenReturn(oaFormItem);
    when(oaFormItemMapper.selectForCostCompletion(OA_FORM_ITEM_ID, OA_FORM_ID, BUSINESS_UNIT_TYPE))
        .thenReturn(oaFormItem);
    when(oaFormItemMapper.countRunnableItems(OA_FORM_ID)).thenReturn(1L);
    when(oaFormItemMapper.countCalculatedRunnableItems(OA_FORM_ID)).thenReturn(1L);
    when(costingBuildService.buildByOaFormItem(OA_FORM_ITEM_ID, PERIOD_MONTH))
        .thenReturn(new QuoteBomCostingBuildResponse(
            8801L,
            null,
            OA_FORM_ITEM_ID,
            OA_NO,
            PRODUCT_CODE,
            "NON_BARE",
            PERIOD_MONTH,
            "MFRP-08-BOM",
            1,
            2,
            0,
            Map.of(),
            List.of(),
            LocalDateTime.of(2026, 7, 2, 10, 0)));
    PricePrepareGenerateResult generateResult = new PricePrepareGenerateResult();
    generateResult.setPrepareNo("PPR-MFRP-08");
    generateResult.setOaNo(OA_NO);
    generateResult.setOaFormItemId(OA_FORM_ITEM_ID);
    generateResult.setTopProductCode(PRODUCT_CODE);
    generateResult.setPeriodMonth(PERIOD_MONTH);
    generateResult.setStatus("SUCCESS");
    when(pricePrepareService.generate(any())).thenReturn(generateResult);
    when(readinessService.check(anyString(), anyLong(), anyString(), anyString()))
        .thenReturn(PricePrepareReadinessResult.ready("PPR-MFRP-08", PERIOD_MONTH, "SUCCESS"));
    QuoteCostRunVersion trialVersion = trialVersion();
    when(versionService.createTrial(
            anyString(), eq(OA_FORM_ITEM_ID), eq(PRODUCT_CODE), anyString(), anyString(),
            any(), any()))
        .thenReturn(trialVersion);
    when(versionMapper.update(any(QuoteCostRunVersion.class), any(Wrapper.class))).thenReturn(1);
    when(versionMapper.selectById(trialVersion.getId())).thenReturn(trialVersion);
    when(versionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(trialVersion));
    when(versionNoGenerator.nextVersionNo(OA_FORM_ITEM_ID, PRODUCT_CODE))
        .thenReturn("COST-MFRP-08-V1");
    QuoteCostingWorkspace workspace = new QuoteCostingWorkspace();
    workspace.setOaNo(OA_NO);
    workspace.setOaFormItemId(OA_FORM_ITEM_ID);
    workspace.setProductCode(PRODUCT_CODE);
    workspace.setPeriodMonth(PERIOD_MONTH);
    workspace.setBusinessUnitType(BUSINESS_UNIT_TYPE);
    workspace.setCurrentPrepareNo("PPR-MFRP-08");
    workspace.setInputFingerprint("MFRP-08-FP");
    workspace.setLockVersion(0);
    QuoteCostingWorkspaceService workspaceService = mock(QuoteCostingWorkspaceService.class);
    when(workspaceService.find(OA_FORM_ITEM_ID, PERIOD_MONTH)).thenReturn(Optional.of(workspace));
    when(workspaceService.lockOrCreate(
            OA_NO, OA_FORM_ITEM_ID, PRODUCT_CODE, PERIOD_MONTH, BUSINESS_UNIT_TYPE))
        .thenReturn(workspace);
    when(workspaceService.update(any(QuoteCostingWorkspace.class), eq(0))).thenReturn(workspace);
    QuoteCuAdjustmentCalcService cuAdjustmentCalcService = request -> {
      CostRunContext context = CostRunContext.quote(
          request.form().getOaNo(),
          request.item().getId(),
          request.item().getMaterialNo(),
          request.item().getPackageMethod(),
          request.form().getCustomer(),
          request.item().getBusinessUnitType(),
          request.pricingMonth(),
          request.calcObjectKey());
      context.setPriceOrgCode("210");
      context.setMaterialOrganizationCode("COMMERCIAL");
      context.setCostRunVersionId(trialVersion.getId());
      context.setCostRunNo(trialVersion.getCostRunNo());
      context.setPricePrepareNo(request.oaPricePrepareNo());
      CostRunObjectResult calculated = costRunEngine.run(context);
      BigDecimal total = calculated.getResult().getTotalCost();
      BigDecimal material = calculated.getCostItems().stream()
          .filter(row -> "MATERIAL".equals(row.getCostCode()))
          .map(CostRunCostItemDto::getAmount)
          .findFirst()
          .orElse(BigDecimal.ZERO);
      trialVersion.setTotalCost(total);
      trialVersion.setFinanceMaterialCost(material);
      trialVersion.setOaMaterialCost(material);
      trialVersion.setCuMaterialAdjustment(BigDecimal.ZERO);
      trialVersion.setFinalQuoteAmount(total);
      trialVersion.setPartItemCount(calculated.getPartItems().size());
      trialVersion.setCostItemCount(calculated.getCostItems().size());
      return new QuoteCuAdjustmentCalcResult(
          trialVersion,
          calculated,
          null,
          material,
          material,
          total,
          BigDecimal.ZERO,
          total);
    };

    return new QuoteCostRunWorkbenchServiceImpl(
        oaFormMapper,
        oaFormItemMapper,
        versionMapper,
        resultService,
        partItemMapper,
        costItemMapper,
        mock(QuoteCuMaterialDiffItemMapper.class),
        readinessService,
        versionNoGenerator,
        cuAdjustmentCalcService,
        mock(com.sanhua.marketingcost.service.collaboration.CollaborationCostingGate.class),
        workspaceService);
  }

  private List<CostRunTraceSnapshot> buildTraceSnapshots(
      List<PricePrepareItem> preparedItems,
      List<PriceRangeItem> rangeItems,
      List<CostRunPartItemDto> partDtos) {
    CostRunPartItemMapper partMapper = mock(CostRunPartItemMapper.class);
    CostRunCostItemMapper costMapper = mock(CostRunCostItemMapper.class);
    PricePrepareItemMapper prepareMapper = mock(PricePrepareItemMapper.class);
    MakePartPriceCalcRowMapper makePartMapper = mock(MakePartPriceCalcRowMapper.class);
    PriceLinkedCalcItemMapper linkedCalcMapper = mock(PriceLinkedCalcItemMapper.class);
    PriceLinkedItemMapper linkedItemMapper = mock(PriceLinkedItemMapper.class);
    PriceFixedItemMapper fixedMapper = mock(PriceFixedItemMapper.class);
    PriceRangeItemMapper rangeMapper = mock(PriceRangeItemMapper.class);
    when(partMapper.selectList(any(Wrapper.class))).thenReturn(toStoredParts(partDtos));
    when(costMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(prepareMapper.selectList(any(Wrapper.class))).thenReturn(preparedItems);
    when(rangeMapper.selectById(anyLong())).thenAnswer(invocation -> {
      Long id = invocation.getArgument(0);
      return rangeItems.stream().filter(item -> id.equals(item.getId())).findFirst().orElse(null);
    });
    CostRunTraceSnapshotBuilderImpl builder =
        new CostRunTraceSnapshotBuilderImpl(
            partMapper,
            costMapper,
            prepareMapper,
            makePartMapper,
            linkedCalcMapper,
            linkedItemMapper,
            fixedMapper,
            rangeMapper,
            new ObjectMapper());
    return builder.build(trialVersion());
  }

  private List<CostRunPartItem> toStoredParts(List<CostRunPartItemDto> partDtos) {
    List<CostRunPartItem> rows = new ArrayList<>();
    long id = 13001L;
    for (CostRunPartItemDto dto : partDtos) {
      CostRunPartItem row = new CostRunPartItem();
      row.setId(id++);
      row.setOaNo(OA_NO);
      row.setOaFormItemId(OA_FORM_ITEM_ID);
      row.setCostRunVersionId(7701L);
      row.setCostRunNo("MFRP-08-TRIAL");
      row.setBomRowId(dto.getBomRowId());
      row.setPricePrepareItemId(dto.getPricePrepareItemId());
      row.setProductCode(dto.getProductCode());
      row.setPartCode(dto.getPartCode());
      row.setPartName(dto.getPartName());
      row.setPartDrawingNo(dto.getPartDrawingNo());
      row.setQty(dto.getPartQty());
      row.setMaterial(dto.getMaterial());
      row.setShapeAttr(dto.getShapeAttr());
      row.setPriceSource(dto.getPriceSource());
      row.setUnitPrice(dto.getUnitPrice());
      row.setAmount(dto.getAmount());
      row.setRemark(dto.getRemark());
      row.setBusinessUnitType(BUSINESS_UNIT_TYPE);
      rows.add(row);
    }
    return rows;
  }

  private void assertReadyRangePrice(String code, NormalMaterialPricePrepareResult result) {
    assertThat(result.getStatus())
        .as(code + " status: " + result.getMessage())
        .isEqualTo("READY");
    assertThat(result.getGapType()).as(code + " gap").isNull();
    assertThat(result.getUnitPrice()).isEqualByComparingTo(HIT_UNIT_PRICE);
    assertAmountWithinTolerance(result.getAmount());
    assertThat(result.getPriceSource()).isEqualTo("区间价");
    assertThat(result.getResultRefType()).isEqualTo("RANGE_PRICE");
    assertThat(result.getResultRefId()).as(code + " range item ref").isNotNull();
    assertThat(result.getMessage())
        .contains("行情区间命中", "CU=90000", "range=87501-92500", "field=price_excl_tax");
  }

  private void assertAmountWithinTolerance(BigDecimal actual) {
    assertThat(actual).isNotNull();
    assertThat(actual.subtract(EXPECTED_AMOUNT).abs()).isLessThanOrEqualTo(AMOUNT_TOLERANCE);
  }

  private PriceRangeItemImportRequest factorRangeImportRequest() {
    PriceRangeItemImportRequest request = new PriceRangeItemImportRequest();
    request.setBusinessUnitType(BUSINESS_UNIT_TYPE);
    request.setRangeBasis("FACTOR");
    request.setFactorCode("CU");
    request.setFactorName("电解铜");
    request.setFactorUnit("元/吨");
    request.setPriceUnit("元/件");
    request.setSourceFile("/Users/xiexicheng/Desktop/demo6/3 产品成本计算表（6.25排水泵）.xls");
    request.setSourceSheet("区间铜价");
    request.setImportBatchNo("MFRP-08-DRAIN-PUMP-CU");
    request.setRows(SAMPLE_PART_CODES.stream().map(this::factorRangeRow).toList());
    return request;
  }

  private PriceRangeItemImportRequest.PriceRangeItemImportRow factorRangeRow(String materialCode) {
    PriceRangeItemImportRequest.PriceRangeItemImportRow row =
        new PriceRangeItemImportRequest.PriceRangeItemImportRow();
    row.setMaterialCode(materialCode);
    row.setMaterialName(materialCode + "-排水泵铜件");
    row.setSpecModel("6.25 排水泵");
    row.setUnit("PCS");
    row.setRangeLow(RANGE_LOW);
    row.setRangeHigh(RANGE_HIGH);
    row.setPriceExclTax(HIT_UNIT_PRICE);
    row.setTaxIncluded(false);
    row.setFactorCode("CU");
    row.setEffectiveFrom(LocalDate.of(2026, 7, 1));
    return row;
  }

  private PricePreparePlanItem planItem(BomCostingRow row) {
    PricePreparePlanItem item = new PricePreparePlanItem();
    item.setBomRow(row);
    item.setTopProductCode(PRODUCT_CODE);
    item.setMaterialCode(row.getMaterialCode());
    item.setMaterialName(row.getMaterialName());
    item.setItemType("NORMAL");
    item.setStatus("READY");
    return item;
  }

  private PricePrepareItem toPrepareItem(
      Long id,
      BomCostingRow row,
      NormalMaterialPricePrepareResult result) {
    PricePrepareItem item = new PricePrepareItem();
    item.setId(id);
    item.setPrepareNo("PPR-MFRP-08");
    item.setPeriodMonth(PERIOD_MONTH);
    item.setOaNo(OA_NO);
    item.setOaFormItemId(OA_FORM_ITEM_ID);
    item.setTopProductCode(PRODUCT_CODE);
    item.setBomRowId(row.getId());
    item.setMaterialCode(row.getMaterialCode());
    item.setMaterialName(row.getMaterialName());
    item.setItemType("NORMAL");
    item.setQuantity(row.getQtyPerTop());
    item.setUnitPrice(result.getUnitPrice());
    item.setAmount(result.getAmount());
    item.setPriceSource(result.getPriceSource());
    item.setStatus(result.getStatus());
    item.setResultRefType(result.getResultRefType());
    item.setResultRefId(result.getResultRefId());
    item.setMessage(result.getMessage());
    item.setBusinessUnitType(BUSINESS_UNIT_TYPE);
    return item;
  }

  private BomCostingRow bomRow(String materialCode) {
    BomCostingRow row = new BomCostingRow();
    row.setId("201850160".equals(materialCode) ? 7101L : 7102L);
    row.setOaNo(OA_NO);
    row.setOaFormItemId(OA_FORM_ITEM_ID);
    row.setTopProductCode(PRODUCT_CODE);
    row.setMaterialCode(materialCode);
    row.setMaterialName(materialCode + "-排水泵铜件");
    row.setQtyPerTop(BOM_QTY);
    row.setShapeAttr("采购件");
    row.setMaterialSpec("CU-SPEC");
    row.setPeriodMonth(PERIOD_MONTH);
    row.setBusinessUnitType(BUSINESS_UNIT_TYPE);
    return row;
  }

  private MaterialMaster materialMaster(String materialCode) {
    MaterialMaster master = new MaterialMaster();
    master.setId("201850160".equals(materialCode) ? 7201L : 7202L);
    master.setMaterialCode(materialCode);
    master.setMaterialName(materialCode + "-排水泵铜件");
    master.setShapeAttr("采购件");
    master.setMaterial("电解铜");
    master.setBusinessUnitType(BUSINESS_UNIT_TYPE);
    return master;
  }

  private PriceTypeRoute rangeRoute(String materialCode) {
    return new PriceTypeRoute(
        materialCode,
        MaterialFormAttrEnum.PURCHASED,
        PriceTypeEnum.RANGE,
        1,
        LocalDate.of(2026, 7, 1),
        null,
        "manual",
        "区间价");
  }

  private CostRunCostItemDto totalCostItem(BigDecimal amount) {
    CostRunCostItemDto item = new CostRunCostItemDto();
    item.setCostCode("TOTAL");
    item.setCostName("不含税总成本");
    item.setAmount(amount);
    return item;
  }

  private OaForm sampleOaForm() {
    OaForm form = new OaForm();
    form.setId(OA_FORM_ID);
    form.setOaNo(OA_NO);
    form.setCustomer("排水泵客户");
    form.setCopperPrice(QUOTE_COPPER_PRICE);
    form.setBusinessUnitType(BUSINESS_UNIT_TYPE);
    form.setAccountingPeriodMonth(PERIOD_MONTH);
    return form;
  }

  private OaFormItem sampleOaFormItem() {
    OaFormItem item = new OaFormItem();
    item.setId(OA_FORM_ITEM_ID);
    item.setOaFormId(OA_FORM_ID);
    item.setMaterialNo(PRODUCT_CODE);
    item.setPackageMethod("NON_BARE");
    item.setBusinessUnitType(BUSINESS_UNIT_TYPE);
    return item;
  }

  private QuoteCostRunVersion trialVersion() {
    QuoteCostRunVersion version = new QuoteCostRunVersion();
    version.setId(7701L);
    version.setCostRunNo("MFRP-08-TRIAL");
    version.setOaNo(OA_NO);
    version.setOaFormItemId(OA_FORM_ITEM_ID);
    version.setProductCode(PRODUCT_CODE);
    version.setPricingMonth(PERIOD_MONTH);
    version.setResultPeriod(PERIOD_MONTH);
    version.setPricePrepareNo("PPR-MFRP-08");
    version.setOaPricePrepareNo("PPR-MFRP-08");
    version.setStatus("RUNNING");
    version.setBusinessUnitType(BUSINESS_UNIT_TYPE);
    version.setTrialStartedAt(LocalDateTime.of(2026, 7, 2, 10, 0));
    return version;
  }

  private static List<Object> paramValues(Wrapper<?> wrapper) {
    wrapper.getCustomSqlSegment();
    AbstractWrapper<?, ?, ?> abstractWrapper = (AbstractWrapper<?, ?, ?>) wrapper;
    return List.copyOf(abstractWrapper.getParamNameValuePairs().values());
  }
}
