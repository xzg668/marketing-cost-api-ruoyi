package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.LinkedPriceEnsureRequest;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureResult;
import com.sanhua.marketingcost.dto.MakePartMaterialPriceResolveResult;
import com.sanhua.marketingcost.dto.MakePartPriceGenerateResponse;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomU9Source;
import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import com.sanhua.marketingcost.entity.MakePartPriceGapItem;
import com.sanhua.marketingcost.entity.MaterialScrapRef;
import com.sanhua.marketingcost.enums.MaterialFormAttrEnum;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.MakePartPriceCalcRowMapper;
import com.sanhua.marketingcost.mapper.MakePartPriceGapItemMapper;
import com.sanhua.marketingcost.service.LinkedPriceEnsureService;
import com.sanhua.marketingcost.service.MakePartMaterialPriceResolveService;
import com.sanhua.marketingcost.service.MakePartPriceCalculator;
import com.sanhua.marketingcost.service.MakePartProcessTypePolicy;
import com.sanhua.marketingcost.service.MakePartScrapMappingService;
import com.sanhua.marketingcost.service.MakePartSourceDataService;
import com.sanhua.marketingcost.service.MakePartWeightService;
import com.sanhua.marketingcost.service.MaterialPriceRouterService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

class MakePartPriceGenerationServiceImplTest {

  private MakePartSourceDataService sourceDataService;
  private MakePartWeightService weightService;
  private MakePartScrapMappingService scrapMappingService;
  private MaterialPriceRouterService materialPriceRouterService;
  private LinkedPriceEnsureService linkedPriceEnsureService;
  private MakePartMaterialPriceResolveService priceResolveService;
  private MakePartPriceCalcRowMapper calcRowMapper;
  private MakePartPriceGapItemMapper gapItemMapper;
  private MakePartPriceGenerationServiceImpl service;

  @BeforeEach
  void setUp() {
    sourceDataService = mock(MakePartSourceDataService.class);
    weightService = mock(MakePartWeightService.class);
    scrapMappingService = mock(MakePartScrapMappingService.class);
    materialPriceRouterService = mock(MaterialPriceRouterService.class);
    linkedPriceEnsureService = mock(LinkedPriceEnsureService.class);
    priceResolveService = mock(MakePartMaterialPriceResolveService.class);
    calcRowMapper = mock(MakePartPriceCalcRowMapper.class);
    gapItemMapper = mock(MakePartPriceGapItemMapper.class);
    when(materialPriceRouterService.listCandidates(any(), any(), any())).thenReturn(List.of());
    when(linkedPriceEnsureService.ensure(any())).thenReturn(new LinkedPriceEnsureResult());
    service =
        new MakePartPriceGenerationServiceImpl(
            sourceDataService,
            new MakePartProcessTypePolicy(),
            weightService,
            scrapMappingService,
            materialPriceRouterService,
            linkedPriceEnsureService,
            priceResolveService,
            new MakePartPriceCalculator(),
            calcRowMapper,
            gapItemMapper);
  }

  @Test
  @DisplayName("按单个制造件生成成功并落库")
  void generateByMaterial() {
    stubHappyPath("MAKE-001", List.of(child("RAW-001", "kg")), List.of(scrap("SCRAP-001")));

    MakePartPriceGenerateResponse response =
        service.generateByMaterial("MAKE-001", "COMMERCIAL", "2026-05");

    assertThat(response.getParentCount()).isEqualTo(1);
    assertThat(response.getRowCount()).isEqualTo(1);
    assertThat(response.getOkCount()).isEqualTo(1);
    ArgumentCaptor<MakePartPriceCalcRow> captor = ArgumentCaptor.forClass(MakePartPriceCalcRow.class);
    verify(calcRowMapper).insert(captor.capture());
    MakePartPriceCalcRow row = captor.getValue();
    assertThat(row.getCalcBatchId()).startsWith("MPPG-");
    assertThat(row.getPricingMonth()).isEqualTo("2026-05");
    assertThat(row.getPriceComplete()).isTrue();
    assertThat(row.getParentMaterialNo()).isEqualTo("MAKE-001");
    assertThat(row.getCostPrice()).isEqualByComparingTo("4.74450000");
    verify(gapItemMapper, never()).insert(any(MakePartPriceGapItem.class));
  }

  @Test
  @DisplayName("按 OA 生成成功")
  void generateByOa() {
    stubHappyPath("MAKE-001", List.of(child("RAW-001", "kg")), List.of(scrap("SCRAP-001")));

    MakePartPriceGenerateResponse response =
        service.generateByOa("OA-001", "COMMERCIAL", "2026-05");

    assertThat(response.getParentCount()).isEqualTo(1);
    assertThat(response.getTotalCount()).isEqualTo(1);
    assertThat(response.getStatusSummary()).containsEntry("OK", 1);
    verify(sourceDataService).listManufacturedParents("OA-001", "COMMERCIAL", null);
  }

  @Test
  @DisplayName("期间为空时按当前 yyyy-MM 写入价格月份")
  void blankPeriodUsesCurrentMonth() {
    stubHappyPath("MAKE-001", List.of(child("RAW-001", "kg")), List.of(scrap("SCRAP-001")));

    service.generateByMaterial("MAKE-001", "COMMERCIAL", null);

    ArgumentCaptor<MakePartPriceCalcRow> captor = ArgumentCaptor.forClass(MakePartPriceCalcRow.class);
    verify(calcRowMapper).insert(captor.capture());
    assertThat(captor.getValue().getPricingMonth()).isEqualTo(YearMonth.now().toString());
  }

  @Test
  @DisplayName("多 child、多 scrap 能生成多行并汇总")
  void generateMultipleChildrenAndScraps() {
    BomCostingRow parent = parent("MAKE-001");
    BomU9Source rawA = child("RAW-A", "kg");
    BomU9Source rawB = child("RAW-B", "千克");
    when(sourceDataService.listManufacturedParents("OA-001", "COMMERCIAL", null))
        .thenReturn(List.of(parent));
    when(sourceDataService.listDedupedChildren("MAKE-001")).thenReturn(List.of(rawA, rawB));
    when(weightService.resolveWeights(any(), any(), any()))
        .thenReturn(weight("RAW-A"), weight("RAW-B"));
    when(scrapMappingService.listMappings("RAW-A", "COMMERCIAL"))
        .thenReturn(List.of(scrap("SCRAP-A1"), scrap("SCRAP-A2")));
    when(scrapMappingService.listMappings("RAW-B", "COMMERCIAL"))
        .thenReturn(List.of(scrap("SCRAP-B1")));
    when(priceResolveService.resolveMaterialUnitPrice(any(), any(), any(), any(), any()))
        .thenAnswer(invocation -> okPriceByCode(invocation.getArgument(0)));

    MakePartPriceGenerateResponse response =
        service.generateByOa("OA-001", "COMMERCIAL", "2026-05");

    assertThat(response.getRowCount()).isEqualTo(3);
    assertThat(response.getOkCount()).isEqualTo(3);
    ArgumentCaptor<MakePartPriceCalcRow> captor = ArgumentCaptor.forClass(MakePartPriceCalcRow.class);
    verify(calcRowMapper, times(3)).insert(captor.capture());
    assertThat(captor.getAllValues()).extracting(MakePartPriceCalcRow::getScrapCode)
        .containsExactly("SCRAP-A1", "SCRAP-A2", "SCRAP-B1");
    assertThat(captor.getAllValues()).allSatisfy(row ->
        assertThat(row.getParentTotalCostPrice()).isEqualByComparingTo("14.23350000"));
  }

  @Test
  @DisplayName("异常场景能写入状态和备注")
  void writesErrorRows() {
    BomCostingRow parent = parent("MAKE-ERR");
    when(sourceDataService.listManufacturedParents("OA-001", "COMMERCIAL", null))
        .thenReturn(List.of(parent));
    when(sourceDataService.listDedupedChildren("MAKE-ERR")).thenReturn(List.of());

    MakePartPriceGenerateResponse response =
        service.generateByOa("OA-001", "COMMERCIAL", "2026-05");

    assertThat(response.getErrorCount()).isEqualTo(1);
    assertThat(response.getStatusSummary()).containsEntry("MISSING_BOM", 1);
    ArgumentCaptor<MakePartPriceCalcRow> captor = ArgumentCaptor.forClass(MakePartPriceCalcRow.class);
    verify(calcRowMapper).insert(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo("MISSING_BOM");
    assertThat(captor.getValue().getPriceComplete()).isFalse();
    assertThat(captor.getValue().getRemark()).contains("缺 U9 直接子项", "上游异常");
    verify(gapItemMapper, never()).insert(any(MakePartPriceGapItem.class));
  }

  @Test
  @DisplayName("缺原材料价：主表保留原材料和废料关系，并生成 RAW 缺价清单")
  void missingRawPriceWritesRawGapItem() {
    stubHappyPath("MAKE-001", List.of(child("RAW-MISS", "kg")), List.of(scrap("SCRAP-001")));
    when(priceResolveService.resolveMaterialUnitPrice("RAW-MISS", "2026-05",
            LocalDate.parse("2026-05-31"), "OA-001", "COMMERCIAL"))
        .thenReturn(MakePartMaterialPriceResolveResult.miss(
            "RAW-MISS", "MISSING_PRICE", "缺原材料价格", "固定价"));

    service.generateByMaterial("MAKE-001", "COMMERCIAL", "2026-05");

    ArgumentCaptor<MakePartPriceCalcRow> rowCaptor = ArgumentCaptor.forClass(MakePartPriceCalcRow.class);
    verify(calcRowMapper).insert(rowCaptor.capture());
    assertThat(rowCaptor.getValue().getChildMaterialNo()).isEqualTo("RAW-MISS");
    assertThat(rowCaptor.getValue().getScrapCode()).isEqualTo("SCRAP-001");
    assertThat(rowCaptor.getValue().getPriceComplete()).isFalse();

    ArgumentCaptor<MakePartPriceGapItem> gapCaptor =
        ArgumentCaptor.forClass(MakePartPriceGapItem.class);
    verify(gapItemMapper).insert(gapCaptor.capture());
    MakePartPriceGapItem gap = gapCaptor.getValue();
    assertThat(gap.getPricingMonth()).isEqualTo("2026-05");
    assertThat(gap.getParentMaterialNo()).isEqualTo("MAKE-001");
    assertThat(gap.getChildMaterialNo()).isEqualTo("RAW-MISS");
    assertThat(gap.getScrapCode()).isEqualTo("SCRAP-001");
    assertThat(gap.getMissingPriceRole()).isEqualTo("RAW");
    assertThat(gap.getMissingMaterialNo()).isEqualTo("RAW-MISS");
    assertThat(gap.getOaPushStatus()).isEqualTo("NOT_PUSHED");
    assertThat(gap.getReason()).contains("原材料价缺失");
  }

  @Test
  @DisplayName("缺废料价：主表保留原材料和废料关系，并生成 SCRAP 缺价清单")
  void missingScrapPriceWritesScrapGapItem() {
    stubHappyPath("MAKE-001", List.of(child("RAW-001", "kg")), List.of(scrap("SCRAP-MISS")));
    when(priceResolveService.resolveMaterialUnitPrice("SCRAP-MISS", "2026-05",
            LocalDate.parse("2026-05-31"), "OA-001", "COMMERCIAL"))
        .thenReturn(MakePartMaterialPriceResolveResult.miss(
            "SCRAP-MISS", "MISSING_PRICE", "缺废料价格", "固定价"));

    service.generateByMaterial("MAKE-001", "COMMERCIAL", "2026-05");

    ArgumentCaptor<MakePartPriceCalcRow> rowCaptor = ArgumentCaptor.forClass(MakePartPriceCalcRow.class);
    verify(calcRowMapper).insert(rowCaptor.capture());
    assertThat(rowCaptor.getValue().getChildMaterialNo()).isEqualTo("RAW-001");
    assertThat(rowCaptor.getValue().getScrapCode()).isEqualTo("SCRAP-MISS");
    assertThat(rowCaptor.getValue().getPriceComplete()).isFalse();

    ArgumentCaptor<MakePartPriceGapItem> gapCaptor =
        ArgumentCaptor.forClass(MakePartPriceGapItem.class);
    verify(gapItemMapper).insert(gapCaptor.capture());
    MakePartPriceGapItem gap = gapCaptor.getValue();
    assertThat(gap.getMissingPriceRole()).isEqualTo("SCRAP");
    assertThat(gap.getMissingMaterialNo()).isEqualTo("SCRAP-MISS");
    assertThat(gap.getChildMaterialNo()).isEqualTo("RAW-001");
    assertThat(gap.getScrapCode()).isEqualTo("SCRAP-MISS");
    assertThat(gap.getReason()).contains("废料价缺失");
  }

  @Test
  @DisplayName("原材料价和废料价都缺：同一明细生成 RAW 与 SCRAP 两条缺价清单")
  void missingRawAndScrapPriceWritesTwoGapItems() {
    stubHappyPath("MAKE-001", List.of(child("RAW-MISS", "kg")), List.of(scrap("SCRAP-MISS")));
    when(priceResolveService.resolveMaterialUnitPrice("RAW-MISS", "2026-05",
            LocalDate.parse("2026-05-31"), "OA-001", "COMMERCIAL"))
        .thenReturn(MakePartMaterialPriceResolveResult.miss(
            "RAW-MISS", "MISSING_PRICE", "缺原材料价格", null));
    when(priceResolveService.resolveMaterialUnitPrice("SCRAP-MISS", "2026-05",
            LocalDate.parse("2026-05-31"), "OA-001", "COMMERCIAL"))
        .thenReturn(MakePartMaterialPriceResolveResult.miss(
            "SCRAP-MISS", "MISSING_PRICE", "缺废料价格", null));

    service.generateByMaterial("MAKE-001", "COMMERCIAL", "2026-05");

    ArgumentCaptor<MakePartPriceGapItem> gapCaptor =
        ArgumentCaptor.forClass(MakePartPriceGapItem.class);
    verify(gapItemMapper, times(2)).insert(gapCaptor.capture());
    assertThat(gapCaptor.getAllValues())
        .extracting(MakePartPriceGapItem::getMissingPriceRole)
        .containsExactly("RAW", "SCRAP");
    assertThat(gapCaptor.getAllValues())
        .extracting(MakePartPriceGapItem::getMissingMaterialNo)
        .containsExactly("RAW-MISS", "SCRAP-MISS");
  }

  @Test
  @DisplayName("缺废料映射：主表保留原材料，不生成 SCRAP 补价行")
  void missingScrapMappingDoesNotCreateScrapGapWithoutScrapCode() {
    stubHappyPath("MAKE-001", List.of(child("RAW-001", "kg")), List.of());

    service.generateByMaterial("MAKE-001", "COMMERCIAL", "2026-05");

    ArgumentCaptor<MakePartPriceCalcRow> rowCaptor = ArgumentCaptor.forClass(MakePartPriceCalcRow.class);
    verify(calcRowMapper).insert(rowCaptor.capture());
    assertThat(rowCaptor.getValue().getChildMaterialNo()).isEqualTo("RAW-001");
    assertThat(rowCaptor.getValue().getScrapCode()).isNull();
    assertThat(rowCaptor.getValue().getStatus())
        .isEqualTo(MakePartPriceCalculator.STATUS_MISSING_SCRAP_MAPPING);
    verify(gapItemMapper, never()).insert(any(MakePartPriceGapItem.class));
  }

  @Test
  @DisplayName("同料号和回收代码重复生成：存在则更新，不存在才新增")
  void repeatedGenerationUpdatesExistingRowsByParentAndScrapCode() {
    stubHappyPath("MAKE-001", List.of(child("RAW-001", "kg")), List.of(scrap("SCRAP-001")));
    MakePartPriceCalcRow existing = new MakePartPriceCalcRow();
    existing.setId(99L);
    when(calcRowMapper.selectList(any())).thenReturn(List.of(), List.of(existing));

    MakePartPriceGenerateResponse first =
        service.generateByOa("OA-001", "COMMERCIAL", "2026-05");
    MakePartPriceGenerateResponse second =
        service.generateByOa("OA-001", "COMMERCIAL", "2026-05");

    assertThat(first.getCalcBatchId()).isNotEqualTo(second.getCalcBatchId());
    verify(calcRowMapper).insert(any(MakePartPriceCalcRow.class));
    ArgumentCaptor<MakePartPriceCalcRow> updateCaptor =
        ArgumentCaptor.forClass(MakePartPriceCalcRow.class);
    verify(calcRowMapper).updateById(updateCaptor.capture());
    assertThat(updateCaptor.getValue().getId()).isEqualTo(99L);
  }

  @Test
  @DisplayName("原材料命中联动价时先 ensure，再正式取价")
  void linkedRawMaterialEnsuredBeforeResolvingPrice() {
    stubHappyPath("MAKE-001", List.of(child("RAW-001", "kg")), List.of(scrap("SCRAP-001")));
    when(materialPriceRouterService.listCandidates("RAW-001", "2026-05", LocalDate.parse("2026-05-31")))
        .thenReturn(List.of(linkedRoute("RAW-001")));

    service.generateByOa("OA-001", "COMMERCIAL", "2026-05");

    verify(linkedPriceEnsureService).ensure(argThat(request ->
        request != null
            && request.getCalcScene() != null
            && "QUOTE".equals(request.getCalcScene().getCode())
            && "OA-001".equals(request.getOaNo())
            && request.normalizedItemCodes().contains("RAW-001")));
    InOrder inOrder = Mockito.inOrder(linkedPriceEnsureService, priceResolveService);
    inOrder.verify(linkedPriceEnsureService).ensure(any(LinkedPriceEnsureRequest.class));
    inOrder.verify(priceResolveService).resolveMaterialUnitPrice(
        "RAW-001", "2026-05", LocalDate.parse("2026-05-31"), "OA-001", "COMMERCIAL");
  }

  @Test
  @DisplayName("废料命中联动价时也会 ensure")
  void linkedScrapEnsuredBeforeResolvingPrice() {
    stubHappyPath("MAKE-001", List.of(child("RAW-001", "kg")), List.of(scrap("SCRAP-001")));
    when(materialPriceRouterService.listCandidates("SCRAP-001", "2026-05", LocalDate.parse("2026-05-31")))
        .thenReturn(List.of(linkedRoute("SCRAP-001")));

    service.generateByOa("OA-001", "COMMERCIAL", "2026-05");

    verify(linkedPriceEnsureService).ensure(argThat(request ->
        request != null && request.normalizedItemCodes().contains("SCRAP-001")));
  }

  @Test
  @DisplayName("非联动价路由不调用 ensure")
  void nonLinkedRouteDoesNotEnsure() {
    stubHappyPath("MAKE-001", List.of(child("RAW-001", "kg")), List.of(scrap("SCRAP-001")));
    when(materialPriceRouterService.listCandidates(any(), any(), any()))
        .thenReturn(List.of(fixedRoute("RAW-001")));

    service.generateByOa("OA-001", "COMMERCIAL", "2026-05");

    verify(linkedPriceEnsureService, never()).ensure(any(LinkedPriceEnsureRequest.class));
  }

  @Test
  @DisplayName("ensure 失败时结果行保留明确备注")
  void ensureFailureRemarkWrittenToRows() {
    stubHappyPath("MAKE-001", List.of(child("RAW-001", "kg")), List.of(scrap("SCRAP-001")));
    when(materialPriceRouterService.listCandidates("RAW-001", "2026-05", LocalDate.parse("2026-05-31")))
        .thenReturn(List.of(linkedRoute("RAW-001")));
    LinkedPriceEnsureResult ensureResult = new LinkedPriceEnsureResult();
    ensureResult.addFailedItem("RAW-001", "联动价公式缺失");
    when(linkedPriceEnsureService.ensure(any())).thenReturn(ensureResult);

    service.generateByOa("OA-001", "COMMERCIAL", "2026-05");

    ArgumentCaptor<MakePartPriceCalcRow> captor = ArgumentCaptor.forClass(MakePartPriceCalcRow.class);
    verify(calcRowMapper).insert(captor.capture());
    assertThat(captor.getValue().getRemark())
        .contains("原材料联动价 ensure 失败", "RAW-001", "联动价公式缺失");
  }

  @Test
  @DisplayName("查询最新批次委托 Mapper 返回最新一次结果")
  void findLatestBatchId() {
    when(calcRowMapper.selectLatestBatchId("OA-001", "COMMERCIAL", "MAKE-001"))
        .thenReturn("MPPG-latest");

    String latest = service.findLatestBatchId("OA-001", "COMMERCIAL", "MAKE-001");

    assertThat(latest).isEqualTo("MPPG-latest");
  }

  private void stubHappyPath(
      String parentCode, List<BomU9Source> children, List<MaterialScrapRef> scraps) {
    BomCostingRow parent = parent(parentCode);
    when(sourceDataService.listManufacturedParents(null, "COMMERCIAL", null))
        .thenReturn(List.of(parent));
    when(sourceDataService.listManufacturedParents("OA-001", "COMMERCIAL", null))
        .thenReturn(List.of(parent));
    when(sourceDataService.listDedupedChildren(parentCode)).thenReturn(children);
    for (BomU9Source child : children) {
      when(weightService.resolveWeights(parentCode, child, "原材料加工"))
          .thenReturn(weight(child.getChildMaterialNo()));
      when(scrapMappingService.listMappings(child.getChildMaterialNo(), "COMMERCIAL"))
          .thenReturn(scraps);
    }
    when(priceResolveService.resolveMaterialUnitPrice(any(), any(), any(), any(), any()))
        .thenAnswer(invocation -> okPriceByCode(invocation.getArgument(0)));
  }

  private BomCostingRow parent(String code) {
    BomCostingRow row = new BomCostingRow();
    row.setOaNo("OA-001");
    row.setBusinessUnitType("COMMERCIAL");
    row.setMaterialCode(code);
    row.setMaterialName("制造件");
    row.setMaterialSpec("DRW-001");
    row.setShapeAttr("制造件");
    return row;
  }

  private BomU9Source child(String code, String stockUnit) {
    BomU9Source row = new BomU9Source();
    row.setParentMaterialNo("MAKE-001");
    row.setChildMaterialNo(code);
    row.setChildMaterialName("原材料" + code);
    row.setChildMaterialSpec("SPEC-" + code);
    row.setStockUnit(stockUnit);
    row.setQtyPerParent(new BigDecimal("0.080"));
    return row;
  }

  private MaterialScrapRef scrap(String code) {
    MaterialScrapRef row = new MaterialScrapRef();
    row.setMaterialCode("RAW");
    row.setScrapCode(code);
    row.setScrapName("废料" + code);
    return row;
  }

  private com.sanhua.marketingcost.dto.MakePartWeightResult weight(String childCode) {
    return com.sanhua.marketingcost.dto.MakePartWeightResult.of(
        "MAKE-001",
        childCode,
        "原材料加工",
        new BigDecimal("80"),
        new BigDecimal("55"),
        "OK",
        "");
  }

  private MakePartMaterialPriceResolveResult okPrice(String materialCode, String price) {
    return MakePartMaterialPriceResolveResult.ok(
        materialCode, "固定价", new BigDecimal(price), "", "trace@" + LocalDate.parse("2026-05-31"));
  }

  private MakePartMaterialPriceResolveResult okPriceByCode(String materialCode) {
    String price = materialCode != null && materialCode.startsWith("SCRAP") ? "75.66" : "82.95";
    return okPrice(materialCode, price);
  }

  private PriceTypeRoute linkedRoute(String materialCode) {
    return new PriceTypeRoute(
        materialCode,
        MaterialFormAttrEnum.PURCHASED,
        PriceTypeEnum.LINKED,
        1,
        null,
        null,
        "manual",
        "联动价");
  }

  private PriceTypeRoute fixedRoute(String materialCode) {
    return new PriceTypeRoute(
        materialCode,
        MaterialFormAttrEnum.PURCHASED,
        PriceTypeEnum.FIXED,
        1,
        null,
        null,
        "manual",
        "固定价");
  }
}
