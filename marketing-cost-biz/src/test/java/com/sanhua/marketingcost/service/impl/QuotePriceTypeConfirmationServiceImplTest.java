package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.PackageSnapshotRequest;
import com.sanhua.marketingcost.dto.PackageSnapshotResult;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.dto.priceprepare.PricePreparePlanItem;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeAdjustRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeConfirmRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeConfirmationActionResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeConfirmationResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeImportMissingRequest;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.MaterialPriceType;
import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.PackageComponentSnapshot;
import com.sanhua.marketingcost.entity.PackageComponentSnapshotDetail;
import com.sanhua.marketingcost.entity.QuoteBomConfirmation;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import com.sanhua.marketingcost.entity.QuotePriceTypeConfirmBatch;
import com.sanhua.marketingcost.entity.QuotePriceTypeConfirmItem;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.MaterialPriceTypeMapper;
import com.sanhua.marketingcost.mapper.MakePartPriceCalcRowMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomConfirmationMapper;
import com.sanhua.marketingcost.mapper.QuoteBomStatusMapper;
import com.sanhua.marketingcost.mapper.QuotePriceTypeConfirmBatchMapper;
import com.sanhua.marketingcost.mapper.QuotePriceTypeConfirmItemMapper;
import com.sanhua.marketingcost.service.MaterialPriceRouterService;
import com.sanhua.marketingcost.service.MakePartPriceGenerationService;
import com.sanhua.marketingcost.service.PackageComponentSnapshotService;
import com.sanhua.marketingcost.service.PricePrepareItemClassifier;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QuotePriceTypeConfirmationServiceImplTest {

  private OaFormMapper oaFormMapper;
  private OaFormItemMapper oaFormItemMapper;
  private QuoteBomStatusMapper quoteBomStatusMapper;
  private BomCostingRowMapper bomCostingRowMapper;
  private QuoteBomConfirmationMapper bomConfirmationMapper;
  private MaterialPriceRouterService materialPriceRouterService;
  private MaterialPriceTypeMapper materialPriceTypeMapper;
  private PricePrepareItemClassifier itemClassifier;
  private PackageComponentSnapshotService packageSnapshotService;
  private MakePartPriceGenerationService makePartPriceGenerationService;
  private MakePartPriceCalcRowMapper makePartPriceCalcRowMapper;
  private QuotePriceTypeConfirmBatchMapper batchMapper;
  private QuotePriceTypeConfirmItemMapper itemMapper;
  private QuotePriceTypeConfirmationInvalidationService priceTypeInvalidationService;
  private QuotePriceTypeConfirmationServiceImpl service;

  @BeforeEach
  void setUp() {
    oaFormMapper = mock(OaFormMapper.class);
    oaFormItemMapper = mock(OaFormItemMapper.class);
    quoteBomStatusMapper = mock(QuoteBomStatusMapper.class);
    bomCostingRowMapper = mock(BomCostingRowMapper.class);
    bomConfirmationMapper = mock(QuoteBomConfirmationMapper.class);
    materialPriceRouterService = mock(MaterialPriceRouterService.class);
    materialPriceTypeMapper = mock(MaterialPriceTypeMapper.class);
    itemClassifier = mock(PricePrepareItemClassifier.class);
    packageSnapshotService = mock(PackageComponentSnapshotService.class);
    makePartPriceGenerationService = mock(MakePartPriceGenerationService.class);
    makePartPriceCalcRowMapper = mock(MakePartPriceCalcRowMapper.class);
    batchMapper = mock(QuotePriceTypeConfirmBatchMapper.class);
    itemMapper = mock(QuotePriceTypeConfirmItemMapper.class);
    priceTypeInvalidationService = mock(QuotePriceTypeConfirmationInvalidationService.class);
    service =
        new QuotePriceTypeConfirmationServiceImpl(
            oaFormMapper,
            oaFormItemMapper,
            quoteBomStatusMapper,
            bomCostingRowMapper,
            bomConfirmationMapper,
            materialPriceRouterService,
            materialPriceTypeMapper,
            itemClassifier,
            packageSnapshotService,
            makePartPriceGenerationService,
            makePartPriceCalcRowMapper,
            batchMapper,
            itemMapper,
            priceTypeInvalidationService);
  }

  @Test
  void getConfirmationRejectsWhenBomNotConfirmed() {
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item());
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status());
    when(bomConfirmationMapper.selectOne(any())).thenReturn(null);

    assertThatThrownBy(() -> service.getConfirmation("OA-001", 10L, null))
        .isInstanceOf(QuoteIngestException.class)
        .hasMessageContaining("请先确认报价物料");
  }

  @Test
  void normalRowsShowConfiguredAndMissingType() {
    mockScope();
    BomCostingRow ok = row(301L, "MAT-OK");
    BomCostingRow missing = row(302L, "MAT-MISS");
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(List.of(ok, missing));
    when(itemClassifier.classify(any())).thenReturn(List.of(normalPlan(ok), normalPlan(missing)));
    when(materialPriceRouterService.resolve(eq("MAT-OK"), eq("2026-06"), any(LocalDate.class)))
        .thenReturn(Optional.of(route(PriceTypeEnum.FIXED)));
    when(materialPriceRouterService.resolve(eq("MAT-MISS"), eq("2026-06"), any(LocalDate.class)))
        .thenReturn(Optional.empty());

    QuotePriceTypeConfirmationResponse response =
        service.getConfirmation("OA-001", 10L, null);

    assertThat(response.getBomConfirmNo()).isEqualTo("BOM-CF-001");
    assertThat(response.getRows()).extracting("materialCode").containsExactly("MAT-OK", "MAT-MISS");
    assertThat(response.getRows()).extracting("typeStatus")
        .containsExactly("CONFIRMED", "MISSING_TYPE");
    assertThat(response.getSummary().getConfiguredTypeCount()).isEqualTo(1);
    assertThat(response.getSummary().getMissingTypeCount()).isEqualTo(1);
  }

  @Test
  void makePartParentAggregatesRawAndScrapChildren() {
    mockScope();
    BomCostingRow parent = row(303L, "MAKE-1");
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(List.of(parent));
    when(itemClassifier.classify(any())).thenReturn(List.of(makePlan(parent)));
    when(makePartPriceCalcRowMapper.selectList(any())).thenReturn(List.of(makeCalcRow()));
    when(materialPriceRouterService.resolve(eq("RAW-1"), eq("2026-06"), any(LocalDate.class)))
        .thenReturn(Optional.empty());
    when(materialPriceRouterService.resolve(eq("SCRAP-1"), eq("2026-06"), any(LocalDate.class)))
        .thenReturn(Optional.of(route(PriceTypeEnum.LINKED)));

    QuotePriceTypeConfirmationResponse response =
        service.getConfirmation("OA-001", 10L, null);

    assertThat(response.getRows().get(0).getObjectType()).isEqualTo("MAKE_PARENT");
    assertThat(response.getRows().get(0).getTypeStatus()).isEqualTo("CHILD_MISSING_TYPE");
    assertThat(response.getRows().get(0).getChildren()).extracting("objectType")
        .containsExactly("MAKE_RAW", "MAKE_SCRAP");
    verify(makePartPriceGenerationService).generateByOa("OA-001", "COMMERCIAL", "2026-06");
    assertThat(response.getSummary().getMakePartCount()).isEqualTo(1);
    assertThat(response.getSummary().getMissingTypeCount()).isEqualTo(1);
  }

  @Test
  void packageParentAggregatesChildMissingType() {
    mockScope();
    BomCostingRow parent = row(304L, "PKG-1");
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(List.of(parent));
    when(itemClassifier.classify(any())).thenReturn(List.of(packagePlan(parent)));
    when(packageSnapshotService.ensureSnapshot(any())).thenReturn(packageSnapshot());
    when(materialPriceRouterService.resolve(eq("PKG-CHILD-1"), eq("2026-06"), any(LocalDate.class)))
        .thenReturn(Optional.empty());

    QuotePriceTypeConfirmationResponse response =
        service.getConfirmation("OA-001", 10L, null);

    assertThat(response.getRows().get(0).getObjectType()).isEqualTo("PACKAGE_PARENT");
    assertThat(response.getRows().get(0).getTypeStatus()).isEqualTo("CHILD_MISSING_TYPE");
    assertThat(response.getRows().get(0).getChildren().get(0).getObjectType()).isEqualTo("PACKAGE_CHILD");
    assertThat(response.getSummary().getPackageComponentCount()).isEqualTo(1);
    assertThat(response.getSummary().getMissingTypeCount()).isEqualTo(1);
    ArgumentCaptor<PackageSnapshotRequest> requestCaptor =
        ArgumentCaptor.forClass(PackageSnapshotRequest.class);
    verify(packageSnapshotService).ensureSnapshot(requestCaptor.capture());
    assertThat(requestCaptor.getValue().getSourceType()).isEqualTo("U9");
  }

  @Test
  void importMissingCreatesMaterialPriceTypeWhenNoEffectiveTypeExists() {
    mockScope();
    BomCostingRow row = row(301L, "MAT-MISS");
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(List.of(row));
    when(itemClassifier.classify(any())).thenReturn(List.of(normalPlan(row)));
    when(materialPriceRouterService.resolve(eq("MAT-MISS"), eq("2026-06"), any(LocalDate.class)))
        .thenReturn(Optional.empty());
    when(materialPriceTypeMapper.selectList(any())).thenReturn(List.of());

    QuotePriceTypeImportMissingRequest request = new QuotePriceTypeImportMissingRequest();
    QuotePriceTypeImportMissingRequest.Item item = new QuotePriceTypeImportMissingRequest.Item();
    item.setMaterialCode("MAT-MISS");
    item.setMaterialName("缺失料");
    item.setObjectType("NORMAL");
    item.setPriceType("固定价");
    item.setEffectiveFrom("2026-06");
    request.setItems(List.of(item));

    QuotePriceTypeConfirmationActionResponse response =
        service.importMissing("OA-001", 10L, request);

    assertThat(response.getResults().get(0).getStatus()).isEqualTo("SUCCESS");
    ArgumentCaptor<MaterialPriceType> captor = ArgumentCaptor.forClass(MaterialPriceType.class);
    verify(materialPriceTypeMapper).insert(captor.capture());
    assertThat(captor.getValue().getMaterialCode()).isEqualTo("MAT-MISS");
    assertThat(captor.getValue().getPriceType()).isEqualTo("固定价");
    assertThat(captor.getValue().getEffectiveFrom()).isEqualTo(LocalDate.parse("2026-06-01"));
  }

  @Test
  void importMissingAcceptsFrontendCodeAndFullDate() {
    mockScope();
    BomCostingRow row = row(301L, "MAT-MISS");
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(List.of(row));
    when(itemClassifier.classify(any())).thenReturn(List.of(normalPlan(row)));
    when(materialPriceRouterService.resolve(eq("MAT-MISS"), eq("2026-06"), any(LocalDate.class)))
        .thenReturn(Optional.empty());
    when(materialPriceTypeMapper.selectList(any())).thenReturn(List.of());

    QuotePriceTypeImportMissingRequest request = new QuotePriceTypeImportMissingRequest();
    QuotePriceTypeImportMissingRequest.Item item = new QuotePriceTypeImportMissingRequest.Item();
    item.setMaterialCode("MAT-MISS");
    item.setObjectType("NORMAL");
    item.setPriceType("FIXED");
    item.setEffectiveFrom("2026-06-15");
    request.setItems(List.of(item));

    QuotePriceTypeConfirmationActionResponse response =
        service.importMissing("OA-001", 10L, request);

    assertThat(response.getResults().get(0).getStatus()).isEqualTo("SUCCESS");
    ArgumentCaptor<MaterialPriceType> captor = ArgumentCaptor.forClass(MaterialPriceType.class);
    verify(materialPriceTypeMapper).insert(captor.capture());
    assertThat(captor.getValue().getPriceType()).isEqualTo("固定价");
    assertThat(captor.getValue().getEffectiveFrom()).isEqualTo(LocalDate.parse("2026-06-01"));
  }

  @Test
  void importMissingNormalizesSettlePriceType() {
    mockScope();
    BomCostingRow row = row(301L, "721250136");
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(List.of(row));
    when(itemClassifier.classify(any())).thenReturn(List.of(normalPlan(row)));
    when(materialPriceRouterService.resolve(eq("721250136"), eq("2026-06"), any(LocalDate.class)))
        .thenReturn(Optional.empty());
    when(materialPriceTypeMapper.selectList(any())).thenReturn(List.of());

    QuotePriceTypeImportMissingRequest request = new QuotePriceTypeImportMissingRequest();
    QuotePriceTypeImportMissingRequest.Item item = new QuotePriceTypeImportMissingRequest.Item();
    item.setMaterialCode("721250136");
    item.setObjectType("NORMAL");
    item.setPriceType("结算价");
    item.setEffectiveFrom("2026-06");
    request.setItems(List.of(item));

    QuotePriceTypeConfirmationActionResponse response =
        service.importMissing("OA-001", 10L, request);

    assertThat(response.getResults().get(0).getStatus()).isEqualTo("SUCCESS");
    ArgumentCaptor<MaterialPriceType> captor = ArgumentCaptor.forClass(MaterialPriceType.class);
    verify(materialPriceTypeMapper).insert(captor.capture());
    assertThat(captor.getValue().getPriceType()).isEqualTo("结算固定价");
    assertThat(captor.getValue().getEffectiveFrom()).isEqualTo(LocalDate.parse("2026-06-01"));
  }

  @Test
  void importMissingRejectsExistingEffectiveTypeWithoutOverride() {
    mockScope();
    BomCostingRow row = row(301L, "MAT-OK");
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(List.of(row));
    when(itemClassifier.classify(any())).thenReturn(List.of(normalPlan(row)));
    when(materialPriceRouterService.resolve(eq("MAT-OK"), eq("2026-06"), any(LocalDate.class)))
        .thenReturn(Optional.empty());
    when(materialPriceTypeMapper.selectList(any())).thenReturn(List.of(existingType("MAT-OK", "固定价")));

    QuotePriceTypeImportMissingRequest request = new QuotePriceTypeImportMissingRequest();
    QuotePriceTypeImportMissingRequest.Item item = new QuotePriceTypeImportMissingRequest.Item();
    item.setMaterialCode("MAT-OK");
    item.setObjectType("NORMAL");
    item.setPriceType("联动价");
    item.setEffectiveFrom("2026-06");
    request.setItems(List.of(item));

    QuotePriceTypeConfirmationActionResponse response =
        service.importMissing("OA-001", 10L, request);

    assertThat(response.getResults().get(0).getStatus()).isEqualTo("FAILED");
    assertThat(response.getResults().get(0).getMessage()).contains("已存在有效价格类型");
    verify(materialPriceTypeMapper, never()).insert(any(MaterialPriceType.class));
  }

  @Test
  void adjustPriceTypeClosesOldVersionAndInsertsNewVersion() {
    mockScope();
    MaterialPriceType existing = existingType("MAT-OK", "固定价");
    existing.setEffectiveFrom(LocalDate.parse("2026-06-01"));
    when(materialPriceTypeMapper.selectList(any())).thenReturn(List.of(existing));

    QuotePriceTypeAdjustRequest request = new QuotePriceTypeAdjustRequest();
    request.setMaterialCode("MAT-OK");
    request.setObjectType("NORMAL");
    request.setPriceType("联动价");
    request.setEffectiveFrom("2026-07");

    QuotePriceTypeConfirmationActionResponse response =
        service.adjustPriceType("OA-001", 10L, request);

    assertThat(response.getResults().get(0).getStatus()).isEqualTo("SUCCESS");
    assertThat(existing.getEffectiveTo()).isEqualTo(LocalDate.parse("2026-06-30"));
    verify(materialPriceTypeMapper).updateById(existing);
    ArgumentCaptor<MaterialPriceType> captor = ArgumentCaptor.forClass(MaterialPriceType.class);
    verify(materialPriceTypeMapper).insert(captor.capture());
    assertThat(captor.getValue().getPriceType()).isEqualTo("联动价");
    assertThat(captor.getValue().getEffectiveFrom()).isEqualTo(LocalDate.parse("2026-07-01"));
    verify(priceTypeInvalidationService).invalidateByMaterialPriceTypeChanges(any());
    verify(priceTypeInvalidationService).invalidateScope("OA-001", 10L, "FIN-001", "2026-06");
  }

  @Test
  void confirmFailsWhenAnyPriceTypeMissing() {
    mockScope();
    BomCostingRow row = row(301L, "MAT-MISS");
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(List.of(row));
    when(itemClassifier.classify(any())).thenReturn(List.of(normalPlan(row)));
    when(materialPriceRouterService.resolve(eq("MAT-MISS"), eq("2026-06"), any(LocalDate.class)))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.confirm("OA-001", 10L, new QuotePriceTypeConfirmRequest()))
        .isInstanceOf(QuoteIngestException.class)
        .hasMessageContaining("存在缺失价格类型");
    verify(batchMapper, never()).insert(any(QuotePriceTypeConfirmBatch.class));
  }

  @Test
  void confirmCreatesBatchAndItemsWhenAllConfigured() {
    mockScope();
    BomCostingRow row = row(301L, "MAT-OK");
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(List.of(row));
    when(itemClassifier.classify(any())).thenReturn(List.of(normalPlan(row)));
    when(materialPriceRouterService.resolve(eq("MAT-OK"), eq("2026-06"), any(LocalDate.class)))
        .thenReturn(Optional.of(route(PriceTypeEnum.FIXED)));
    when(batchMapper.selectList(any())).thenReturn(List.of());
    when(batchMapper.insert(any(QuotePriceTypeConfirmBatch.class))).thenReturn(1);

    QuotePriceTypeConfirmationActionResponse response =
        service.confirm("OA-001", 10L, new QuotePriceTypeConfirmRequest());

    assertThat(response.getConfirmNo()).startsWith("PT-CF-");
    assertThat(response.getStatus()).isEqualTo("CONFIRMED");
    verify(batchMapper).insert(any(QuotePriceTypeConfirmBatch.class));
    ArgumentCaptor<QuotePriceTypeConfirmItem> itemCaptor =
        ArgumentCaptor.forClass(QuotePriceTypeConfirmItem.class);
    verify(itemMapper).insert(itemCaptor.capture());
    assertThat(itemCaptor.getValue().getObjectType()).isEqualTo("NORMAL");
    assertThat(itemCaptor.getValue().getStatus()).isEqualTo("CONFIRMED");
  }

  @Test
  void confirmMarksOldBatchStaleWhenBomConfirmNoChanged() {
    mockScope();
    BomCostingRow row = row(301L, "MAT-OK");
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(List.of(row));
    when(itemClassifier.classify(any())).thenReturn(List.of(normalPlan(row)));
    when(materialPriceRouterService.resolve(eq("MAT-OK"), eq("2026-06"), any(LocalDate.class)))
        .thenReturn(Optional.of(route(PriceTypeEnum.FIXED)));
    QuotePriceTypeConfirmBatch old = new QuotePriceTypeConfirmBatch();
    old.setConfirmNo("PT-CF-OLD");
    old.setBomConfirmNo("BOM-CF-OLD");
    old.setStatus("CONFIRMED");
    when(batchMapper.selectList(any())).thenReturn(List.of(old));
    when(batchMapper.insert(any(QuotePriceTypeConfirmBatch.class))).thenReturn(1);

    service.confirm("OA-001", 10L, new QuotePriceTypeConfirmRequest());

    assertThat(old.getStatus()).isEqualTo("STALE");
    verify(batchMapper).updateById(old);
  }

  private void mockScope() {
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item());
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status());
    when(bomConfirmationMapper.selectOne(any())).thenReturn(bomConfirmation());
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

  private QuoteBomConfirmation bomConfirmation() {
    QuoteBomConfirmation confirmation = new QuoteBomConfirmation();
    confirmation.setId(701L);
    confirmation.setConfirmNo("BOM-CF-001");
    confirmation.setOaNo("OA-001");
    confirmation.setOaFormItemId(10L);
    confirmation.setTopProductCode("FIN-001");
    confirmation.setPeriodMonth("2026-06");
    confirmation.setConfirmStatus("CONFIRMED");
    return confirmation;
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
    row.setPeriodMonth("2026-06");
    row.setLevel(1);
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

  private PriceTypeRoute route(PriceTypeEnum type) {
    return new PriceTypeRoute(
        "MAT", null, type, 1, LocalDate.parse("2026-06-01"), null, "manual", type.getDbText());
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
    return PackageSnapshotResult.of(snapshot, List.of(detail), false);
  }

  private MaterialPriceType existingType(String materialCode, String priceType) {
    MaterialPriceType type = new MaterialPriceType();
    type.setId(901L);
    type.setMaterialCode(materialCode);
    type.setMaterialName(materialCode + " name");
    type.setPriceType(priceType);
    type.setPeriod("2026-06");
    type.setPriority(1);
    type.setEffectiveFrom(LocalDate.parse("2026-06-01"));
    type.setBusinessUnitType("COMMERCIAL");
    return type;
  }
}
