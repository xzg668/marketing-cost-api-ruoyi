package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.PackageSnapshotRequest;
import com.sanhua.marketingcost.dto.PackageSnapshotResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePreparePlanItem;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeRecognitionResponse;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomCostingRowSubRef;
import com.sanhua.marketingcost.entity.MaterialMaster;
import com.sanhua.marketingcost.entity.MaterialScrapRef;
import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.PackageComponentSnapshot;
import com.sanhua.marketingcost.entity.PackageComponentSnapshotDetail;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.BomCostingRowSubRefMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomStatusMapper;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import java.util.Map;
import java.util.Set;
import com.sanhua.marketingcost.service.MakePartPriceGenerationService;
import com.sanhua.marketingcost.service.MakePartScrapMappingService;
import com.sanhua.marketingcost.service.PackageComponentSnapshotService;
import com.sanhua.marketingcost.service.PricePrepareItemClassifier;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

class QuotePriceTypeRecognitionServiceImplTest {

  private OaFormMapper oaFormMapper;
  private OaFormItemMapper oaFormItemMapper;
  private QuoteBomStatusMapper quoteBomStatusMapper;
  private BomCostingRowMapper bomCostingRowMapper;
  private BomCostingRowSubRefMapper bomCostingRowSubRefMapper;
  private MaterialMasterMapper materialMasterMapper;
  private MonthlyQuotationSessions monthlySessions;
  private QuotationPriceSession session;
  private MaterialMasterRawMapper rawMaterials;
  private final Map<String, String> priceKinds = new java.util.HashMap<>();
  private PricePrepareItemClassifier itemClassifier;
  private PackageComponentSnapshotService packageSnapshotService;
  private MakePartPriceGenerationService makePartPriceGenerationService;
  private MakePartScrapMappingService makePartScrapMappingService;
  private com.sanhua.marketingcost.service.QuoteProductBomCostingBuildService costingBoms;
  private QuotePriceTypeRecognitionServiceImpl service;

  @BeforeEach
  void setUp() {
    oaFormMapper = mock(OaFormMapper.class);
    oaFormItemMapper = mock(OaFormItemMapper.class);
    quoteBomStatusMapper = mock(QuoteBomStatusMapper.class);
    bomCostingRowMapper = mock(BomCostingRowMapper.class);
    bomCostingRowSubRefMapper = mock(BomCostingRowSubRefMapper.class);
    materialMasterMapper = mock(MaterialMasterMapper.class);
    monthlySessions = mock(MonthlyQuotationSessions.class, org.mockito.Mockito.withSettings().mockMaker(org.mockito.MockMakers.INLINE));
    session = mock(QuotationPriceSession.class);
    rawMaterials = mock(MaterialMasterRawMapper.class);
    priceKinds.clear();
    when(monthlySessions.open(anyString(), anyString(), anyString(), any(), any())).thenReturn(session);
    when(session.conditions(anyString())).thenReturn(NonCuConditions.empty());
    when(session.read(any())).thenAnswer(call -> {
      var values = new java.util.LinkedHashMap<QuotationPriceSession.Input, QuotationPriceSession.Value>();
      var problems = new java.util.LinkedHashMap<QuotationPriceSession.Input, MonthlyMaterialPrices.Problem>();
      for (var input : (java.util.Collection<QuotationPriceSession.Input>) call.getArgument(0)) {
        String kind = priceKinds.get(input.materialCode());
        if (kind == null) {
          problems.put(input, new MonthlyMaterialPrices.Problem(MonthlyAdoptionRetryPolicy.Failure.MISSING, "没有适用价格"));
          continue;
        }
        var request = new MonthlyPriceRequest(new MonthlyPriceFamilyKey(java.time.YearMonth.parse("2026-06"),
            "COMMERCIAL", input.materialCode(), input.specification(), input.unit(), "CNY", TaxBasis.EXCLUDED, null),
            input.organization(), input.conditions(), input.basisQuantity());
        var evidence = MonthlyAdoptionPlan.SourceEvidence.ofJson("{\"sourceKind\":\"" + kind + "\"}");
        var fact = "HOUSEHOLD_INQUIRY".equals(kind)
            ? MonthlyAdoptionPlan.PriceFact.household(BigDecimal.ONE, BigDecimal.ZERO, evidence)
            : MonthlyAdoptionPlan.PriceFact.normal(BigDecimal.ONE, evidence, Set.of());
        var selection = new MonthlyAdoptionPlan.Selection(1L, new MonthlyPriceCoverage(Set.of(input.organization()),
            QuantityInterval.all(), ConditionDomain.any()), fact);
        values.put(input, new QuotationPriceSession.Value(request, selection, BigDecimal.ONE, BigDecimal.ONE));
      }
      return new QuotationPriceSession.Read(values, problems);
    });
    when(rawMaterials.selectByLatestBatchAndCodes(any(), any(), any())).thenAnswer(call -> {
      return ((java.util.Collection<String>) call.getArgument(0)).stream().map(code -> {
        var material = new com.sanhua.marketingcost.entity.MaterialMasterRaw();
        material.setMaterialCode(code); material.setUnit("件"); return material;
      }).toList();
    });

    itemClassifier = mock(PricePrepareItemClassifier.class);
    packageSnapshotService = mock(PackageComponentSnapshotService.class);
    makePartPriceGenerationService = mock(MakePartPriceGenerationService.class);
    makePartScrapMappingService = mock(MakePartScrapMappingService.class);
    costingBoms = mock(com.sanhua.marketingcost.service.QuoteProductBomCostingBuildService.class);
    when(costingBoms.previewFromEffectiveBom(any(), any())).thenAnswer(call ->
        new com.sanhua.marketingcost.service.QuoteProductBomCostingBuildService.Preview(
            bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", "2026-06"),
            new MonthlyBomPricingScope.Scope(List.of(), List.of(), List.of(), Set.of())));
    service =
        new QuotePriceTypeRecognitionServiceImpl(
            oaFormMapper,
            oaFormItemMapper,
            quoteBomStatusMapper,
            bomCostingRowMapper,
            bomCostingRowSubRefMapper,
            materialMasterMapper,
            monthlySessions, rawMaterials, new MonthlyPriceDocumentCodec(new com.fasterxml.jackson.databind.ObjectMapper()),
            itemClassifier,
            packageSnapshotService,
            makePartPriceGenerationService,
            makePartScrapMappingService, costingBoms);
  }

  @Test
  void getRecognitionRejectsWhenCostingBomHasNotBeenGenerated() {
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item());
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status());
    assertThatThrownBy(() -> service.getRecognition("OA-001", 10L, null))
        .isInstanceOf(QuoteIngestException.class)
        .hasMessageContaining("请先生成报价物料");
  }

  @Test
  void getRecognitionRejectsMixedCurrentBomBuilds() {
    mockScope();
    BomCostingRow first = row(301L, "MAT-1");
    BomCostingRow second = row(302L, "MAT-2");
    second.setBuildBatchId("BOM-BUILD-OLD");
    when(bomCostingRowMapper.selectQuoteCostingSnapshot(
            "OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(List.of(first, second));

    assertThatThrownBy(() -> service.getRecognition("OA-001", 10L, null))
        .isInstanceOf(QuoteIngestException.class)
        .hasMessageContaining("多个构建版本");
    verify(itemClassifier, never()).classify(any());
  }

  @Test
  void newlyApplicableHouseholdScopeReplacesTheOldPackagingViewWithoutWritingIt() {
    mockScope();
    var old = row(301L, "MAT-HH"); old.setPath("/FIN-001/MAT-HH/");
    old.setSettlementRowType("PACKAGE_PARENT");
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(List.of(old));
    var current = row(null, "MAT-HH"); current.setPath(old.getPath());
    current.setSettlementRowType("HOUSEHOLD_INQUIRY");
    when(costingBoms.previewFromEffectiveBom(10L, "BOM-BUILD-001")).thenReturn(
        new com.sanhua.marketingcost.service.QuoteProductBomCostingBuildService.Preview(List.of(current),
            new MonthlyBomPricingScope.Scope(List.of(), List.of(), List.of(), Set.of())));
    priceKinds.put("MAT-HH", "HOUSEHOLD_INQUIRY");
    var recognized = service.getRecognition("OA-001", 10L, null).getRows().getFirst();
    assertThat(recognized.getPriceType()).isEqualTo("家用部件询价");
    assertThat(recognized.getChildren()).isEmpty();
    assertThat(current.getId()).isNull();
    assertThat(old.getSettlementRowType()).isEqualTo("PACKAGE_PARENT");
    verify(packageSnapshotService, never()).previewSnapshot(any());
    verify(costingBoms, never()).buildFromEffectiveBom(any(), any());
  }

  @Test
  void normalRowsShowConfiguredAndMissingType() {
    mockScope();
    BomCostingRow ok = row(301L, "MAT-OK");
    BomCostingRow missing = row(302L, "MAT-MISS");
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(List.of(ok, missing));
    when(itemClassifier.classify(any())).thenReturn(List.of(normalPlan(ok), normalPlan(missing)));
    priceKinds.put("MAT-OK", "PURCHASE_FIXED");


    QuotePriceTypeRecognitionResponse response =
        service.getRecognition("OA-001", 10L, null);

    assertThat(response.getBomBuildBatchId()).isEqualTo("BOM-BUILD-001");
    assertThat(response.getRows()).extracting("materialCode").containsExactly("MAT-OK", "MAT-MISS");
    assertThat(response.getRows()).extracting("typeStatus")
        .containsExactly("RECOGNIZED", "NO_SOURCE");
    assertThat(response.getSummary().getCandidateSourceCount()).isEqualTo(1);
    assertThat(response.getSummary().getMissingSourceCount()).isEqualTo(1);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"SETTLE_FIXED,FIXED,结算固定价", "SETTLE_LINKED,LINKED,结算联动价"})
  void settlementRecognitionKeepsTheIndependentSourceIdentity(String source, PriceTypeEnum bucket, String label) {
    mockScope();
    var row = row(301L, "MAT-SETTLE");
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(List.of(row));
    when(itemClassifier.classify(any())).thenReturn(List.of(normalPlan(row)));
    priceKinds.put("MAT-SETTLE", source);
    var recognized = service.getRecognition("OA-001", 10L, null).getRows().getFirst();
    assertThat(recognized.getTypeStatus()).isEqualTo("RECOGNIZED");
    assertThat(recognized.getPriceType()).isEqualTo(label);
  }

  @Test
  void missingSnapshotNameFallsBackToMaterialMasterByTheSameMaterialCode() {
    mockScope();
    BomCostingRow row = row(301L, "205686659");
    row.setMaterialName(null);
    PricePreparePlanItem plan = normalPlan(row);
    plan.setMaterialName(null);
    when(bomCostingRowMapper.selectQuoteCostingSnapshot(
            "OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(List.of(row));
    when(itemClassifier.classify(any())).thenReturn(List.of(plan));
    MaterialMaster master = new MaterialMaster();
    master.setMaterialCode("205686659");
    master.setMaterialName("接管");
    when(materialMasterMapper.selectList(any())).thenReturn(List.of(master));
    priceKinds.put("205686659", "PURCHASE_FIXED");

    QuotePriceTypeRecognitionResponse response =
        service.getRecognition("OA-001", 10L, null);

    assertThat(response.getRows().getFirst().getMaterialName()).isEqualTo("接管");
  }

  @Test
  void makePartParentAggregatesRawAndScrapChildren() {
    mockScope();
    BomCostingRow parent = row(303L, "MAKE-1");
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(List.of(parent));
    when(itemClassifier.classify(any())).thenReturn(List.of(makePlan(parent)));
    when(makePartPriceGenerationService.previewStructureByOa(
            "OA-001", "COMMERCIAL", "2026-06"))
        .thenReturn(List.of(makeCalcRow()));

    priceKinds.put("SCRAP-1", "LINKED");

    QuotePriceTypeRecognitionResponse response =
        service.getRecognition("OA-001", 10L, null);

    assertThat(response.getRows().get(0).getObjectType()).isEqualTo("MAKE_PARENT");
    assertThat(response.getRows().get(0).getTypeStatus()).isEqualTo("CHILD_GAP");
    assertThat(response.getRows().get(0).getChildren()).extracting("objectType")
        .containsExactly("MAKE_RAW", "MAKE_SCRAP");
    verify(makePartPriceGenerationService)
        .previewStructureByOa("OA-001", "COMMERCIAL", "2026-06");
    assertThat(response.getSummary().getMakePartCount()).isEqualTo(1);
    assertThat(response.getSummary().getMissingSourceCount()).isEqualTo(1);
  }

  @Test
  void specialRollupParentOnlyExpandsMatchedRawMaterialAndKeepsSiblingAsNormalRow() {
    mockScope();
    BomCostingRow parent = row(305L, "2018000671211");
    parent.setMaterialName("封头部件");
    parent.setSettlementRowType("SPECIAL_ROLLUP_PARENT");
    BomCostingRow ring = row(306L, "203240247");
    ring.setMaterialName("分磁环");
    when(bomCostingRowMapper.selectQuoteCostingSnapshot(
            "OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(List.of(parent, ring));
    when(itemClassifier.classify(any()))
        .thenReturn(List.of(makePlan(parent), normalPlan(ring)));
    when(bomCostingRowSubRefMapper.selectSpecialRollupChildren(List.of(305L)))
        .thenReturn(List.of(rollupRef(305L, "301220046", "软磁不锈钢棒", "0.00786000")));
    when(makePartScrapMappingService.listMappings("301220046", "COMMERCIAL"))
        .thenReturn(List.of(scrapRef("301220046", "301990752", "废软磁不锈铁沫")));

    priceKinds.put("301990752", "LINKED");
    priceKinds.put("203240247", "PURCHASE_FIXED");

    QuotePriceTypeRecognitionResponse response =
        service.getRecognition("OA-001", 10L, null);

    assertThat(response.getRows()).hasSize(2);
    assertThat(response.getRows().get(0).getMaterialCode()).isEqualTo("2018000671211");
    assertThat(response.getRows().get(0).getChildren()).extracting("materialCode")
        .containsExactly("301220046", "301990752");
    assertThat(response.getRows().get(0).getChildren()).extracting("objectType")
        .containsExactly("MAKE_RAW", "MAKE_SCRAP");
    assertThat(response.getRows().get(1).getMaterialCode()).isEqualTo("203240247");
    assertThat(response.getRows().get(1).getObjectType()).isEqualTo("NORMAL");
    verify(makePartPriceGenerationService, never())
        .previewStructureByOa(anyString(), anyString(), anyString());
  }

  @Test
  void specialRollupRecognitionNormalizesRepeatedRawQuantityAgainstMergedParentQuantity() {
    mockScope();
    BomCostingRow parent = row(307L, "1053000301622");
    parent.setSettlementRowType("SPECIAL_ROLLUP_PARENT");
    parent.setQtyPerTop(new BigDecimal("4"));
    when(bomCostingRowMapper.selectQuoteCostingSnapshot(
            "OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(List.of(parent));
    when(itemClassifier.classify(any())).thenReturn(List.of(makePlan(parent)));
    when(bomCostingRowSubRefMapper.selectSpecialRollupChildren(List.of(307L)))
        .thenReturn(
            List.of(
                rollupRef(307L, "301260124", "不锈钢板", "0.0053", "0.0106"),
                rollupRef(307L, "301260124", "不锈钢板", "0.0053", "0.0106")));
    when(makePartScrapMappingService.listMappings("301260124", "COMMERCIAL"))
        .thenReturn(List.of());
    priceKinds.put("301260124", "LINKED");

    QuotePriceTypeRecognitionResponse response =
        service.getRecognition("OA-001", 10L, null);

    assertThat(response.getRows()).singleElement().satisfies(row -> {
      assertThat(row.getChildren()).singleElement().satisfies(child -> {
        assertThat(child.getMaterialCode()).isEqualTo("301260124");
        assertThat(child.getQuantity()).isEqualByComparingTo("0.0053");
      });
    });
  }

  @Test
  void packageParentAggregatesChildMissingType() {
    mockScope();
    BomCostingRow parent = row(304L, "PKG-1");
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(List.of(parent));
    when(itemClassifier.classify(any())).thenReturn(List.of(packagePlan(parent)));
    when(packageSnapshotService.previewSnapshot(any())).thenReturn(packageSnapshot());


    QuotePriceTypeRecognitionResponse response =
        service.getRecognition("OA-001", 10L, null);

    assertThat(response.getRows().get(0).getObjectType()).isEqualTo("PACKAGE_PARENT");
    assertThat(response.getRows().get(0).getTypeStatus()).isEqualTo("CHILD_GAP");
    assertThat(response.getRows().get(0).getChildren().get(0).getObjectType()).isEqualTo("PACKAGE_CHILD");
    assertThat(response.getSummary().getPackageComponentCount()).isEqualTo(1);
    assertThat(response.getSummary().getMissingSourceCount()).isEqualTo(1);
    ArgumentCaptor<PackageSnapshotRequest> requestCaptor =
        ArgumentCaptor.forClass(PackageSnapshotRequest.class);
    verify(packageSnapshotService).previewSnapshot(requestCaptor.capture());
    assertThat(requestCaptor.getValue().getSourceType()).isEqualTo("U9");
    assertThat(requestCaptor.getValue().getPriceOrgCode()).isEqualTo("210");
  }

  @Test
  void packageParentUsesCostingRowOrganizationForSnapshot() {
    OaForm form = form();
    form.setProcessCode("FI-SC-006");
    form.setBusinessUnitType("PLATE");
    OaFormItem item = item();
    item.setBusinessUnitType("PLATE");
    item.setProductName("普通产品");
    mockScope(form, item);
    BomCostingRow parent = row(304L, "PKG-1");
    parent.setPriceOrgCode("220");
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(List.of(parent));
    when(itemClassifier.classify(any())).thenReturn(List.of(packagePlan(parent)));
    when(packageSnapshotService.previewSnapshot(any())).thenReturn(packageSnapshot());


    service.getRecognition("OA-001", 10L, null);

    ArgumentCaptor<PackageSnapshotRequest> requestCaptor =
        ArgumentCaptor.forClass(PackageSnapshotRequest.class);
    verify(packageSnapshotService).previewSnapshot(requestCaptor.capture());
    assertThat(requestCaptor.getValue().getPriceOrgCode()).isEqualTo("220");
  }

  @Test
  void packageParentReadsPublishedElectronicDrawingStructure() {
    mockScope();
    QuoteBomStatus drawingStatus = status();
    drawingStatus.setBomSource("ELECTRONIC_DRAWING_BOM");
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(drawingStatus);
    BomCostingRow parent = row(304L, "PKG-1");
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(List.of(parent));
    when(itemClassifier.classify(any())).thenReturn(List.of(packagePlan(parent)));
    when(packageSnapshotService.previewSnapshot(any())).thenReturn(packageSnapshot());

    service.getRecognition("OA-001", 10L, null);

    ArgumentCaptor<PackageSnapshotRequest> requestCaptor =
        ArgumentCaptor.forClass(PackageSnapshotRequest.class);
    verify(packageSnapshotService).previewSnapshot(requestCaptor.capture());
    assertThat(requestCaptor.getValue().getSourceType()).isEqualTo("E_DRAWING");
  }

  @Test
  void priceTypePageReadUsesReadOnlyTransaction() throws Exception {
    Transactional transactional =
        QuotePriceTypeRecognitionServiceImpl.class
            .getMethod("getRecognition", String.class, Long.class, String.class)
            .getAnnotation(Transactional.class);

    assertThat(transactional).isNotNull();
    assertThat(transactional.readOnly()).isTrue();
  }

  private void mockScope() {
    mockScope(form(), item());
  }

  private void mockScope(OaForm form, OaFormItem item) {
    when(oaFormMapper.selectOne(any())).thenReturn(form);
    when(oaFormItemMapper.selectById(10L)).thenReturn(item);
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status());
  }

  private OaForm form() {
    OaForm form = new OaForm();
    form.setId(1L);
    form.setOaNo("OA-001");
    form.setAccountingPeriodMonth("2026-06");
    form.setBusinessUnitType("COMMERCIAL");
    return form;
  }

  private OaFormItem item() {
    OaFormItem item = new OaFormItem();
    item.setId(10L);
    item.setOaFormId(1L);
    item.setMaterialNo("FIN-001");
    item.setBusinessUnitType("COMMERCIAL");
    return item;
  }

  private QuoteBomStatus status() {
    QuoteBomStatus status = new QuoteBomStatus();
    status.setOaNo("OA-001");
    status.setOaFormItemId(10L);
    status.setProductCode("FIN-001");
    status.setCostPeriodMonth("2026-06");
    return status;
  }

  private BomCostingRow row(Long id, String materialCode) {
    BomCostingRow row = new BomCostingRow();
    row.setId(id);
    row.setOaNo("OA-001");
    row.setOaFormItemId(10L);
    row.setTopProductCode("FIN-001");
    row.setParentCode("FIN-001");
    row.setMaterialCode(materialCode);
    row.setMaterialName(materialCode + " name");
    row.setQtyPerParent(BigDecimal.ONE);
    row.setQtyPerTop(BigDecimal.ONE);
    row.setUnit("件");
    row.setPeriodMonth("2026-06");
    row.setLevel(1);
    row.setPriceOrgCode("210");
    row.setBuildBatchId("BOM-BUILD-001");
    return row;
  }

  private PricePreparePlanItem normalPlan(BomCostingRow row) {
    return plan(row, PricePrepareItemClassifierImpl.ITEM_TYPE_NORMAL);
  }

  private PricePreparePlanItem makePlan(BomCostingRow row) {
    return plan(row, PricePrepareItemClassifierImpl.ITEM_TYPE_MAKE_PART);
  }

  private PricePreparePlanItem packagePlan(BomCostingRow row) {
    return plan(row, PricePrepareItemClassifierImpl.ITEM_TYPE_PACKAGE_COMPONENT);
  }

  private PricePreparePlanItem plan(BomCostingRow row, String itemType) {
    PricePreparePlanItem plan = new PricePreparePlanItem();
    plan.setBomRow(row);
    plan.setBomRowId(row.getId());
    plan.setMaterialCode(row.getMaterialCode());
    plan.setMaterialName(row.getMaterialName());
    plan.setItemType(itemType);
    return plan;
  }

  private MakePartPriceCalcRow makeCalcRow() {
    MakePartPriceCalcRow row = new MakePartPriceCalcRow();
    row.setParentMaterialNo("MAKE-1");
    row.setParentMaterialName("MAKE-1 name");
    row.setChildMaterialNo("RAW-1");
    row.setChildMaterialName("RAW-1 name");
    row.setQtyPerParent(BigDecimal.ONE);
    row.setScrapCode("SCRAP-1");
    row.setScrapName("废料1");
    return row;
  }

  private BomCostingRowSubRef rollupRef(
      Long costingRowId,
      String materialCode,
      String materialName,
      String quantity) {
    return rollupRef(costingRowId, materialCode, materialName, quantity, null);
  }

  private BomCostingRowSubRef rollupRef(
      Long costingRowId,
      String materialCode,
      String materialName,
      String quantity,
      String quantityPerTop) {
    BomCostingRowSubRef ref = new BomCostingRowSubRef();
    ref.setCostingRowId(costingRowId);
    ref.setRefType("SPECIAL_ROLLUP_CHILD");
    ref.setSubMaterialCode(materialCode);
    ref.setSubMaterialName(materialName);
    ref.setSubQtyPerParent(new BigDecimal(quantity));
    if (quantityPerTop != null) {
      ref.setSubQtyPerTop(new BigDecimal(quantityPerTop));
    }
    return ref;
  }

  private MaterialScrapRef scrapRef(
      String materialCode, String scrapCode, String scrapName) {
    MaterialScrapRef ref = new MaterialScrapRef();
    ref.setMaterialCode(materialCode);
    ref.setScrapCode(scrapCode);
    ref.setScrapName(scrapName);
    return ref;
  }

  private PackageSnapshotResult packageSnapshot() {
    PackageComponentSnapshot snapshot = new PackageComponentSnapshot();
    snapshot.setId(801L);
    snapshot.setPackageMaterialCode("PKG-1");
    snapshot.setPeriodMonth("2026-06");
    snapshot.setStatus("NORMAL");
    PackageComponentSnapshotDetail detail = new PackageComponentSnapshotDetail();
    detail.setSnapshotId(801L);
    detail.setPackageMaterialCode("PKG-1");
    detail.setPeriodMonth("2026-06");
    detail.setChildMaterialCode("PKG-CHILD-1");
    detail.setChildMaterialName("包装子件1");
    detail.setQtyPerParent(BigDecimal.ONE);
    return PackageSnapshotResult.of(snapshot, List.of(detail), false);
  }
}
