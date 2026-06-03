package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.MakePartMaterialPriceResolveResult;
import com.sanhua.marketingcost.dto.MakePartPriceGenerateResponse;
import com.sanhua.marketingcost.dto.MakePartWeightResult;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomU9Source;
import com.sanhua.marketingcost.entity.CostRunPartItem;
import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import com.sanhua.marketingcost.entity.MaterialScrapRef;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.enums.MaterialFormAttrEnum;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.CostRunPartItemMapper;
import com.sanhua.marketingcost.mapper.MakePartPriceCalcRowMapper;
import com.sanhua.marketingcost.mapper.MakePartPriceGapItemMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.LinkedPriceEnsureService;
import com.sanhua.marketingcost.service.MakePartMaterialPriceResolveService;
import com.sanhua.marketingcost.service.MakePartPriceCalculator;
import com.sanhua.marketingcost.service.MakePartProcessTypePolicy;
import com.sanhua.marketingcost.service.MakePartScrapMappingService;
import com.sanhua.marketingcost.service.MakePartSourceDataService;
import com.sanhua.marketingcost.service.MakePartWeightService;
import com.sanhua.marketingcost.service.MaterialPriceRouterService;
import com.sanhua.marketingcost.service.PackageComponentIdentifyService;
import com.sanhua.marketingcost.service.PackageComponentPriceService;
import com.sanhua.marketingcost.service.pricing.MakePartPriceCalcResolver;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class MakePartPriceGenerationE2ETest {

  private static final String OA_NO = "OA-MPPG-E2E";
  private static final String BU = "COMMERCIAL";
  private static final String PERIOD = "2026-05";
  private static final String SAMPLE_PARENT = "203250582";

  @BeforeAll
  static void initTableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), MakePartPriceCalcRow.class);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("MPPG-11：BOM 生成表到实时成本制造件取价端到端样例")
  void generateRowsAndResolveMakePriceForCostRun() {
    setBusinessUnit(BU);
    List<MakePartPriceCalcRow> generatedRows = new ArrayList<>();
    MakePartPriceCalcRowMapper calcRowMapper = mockCalcRowMapper(generatedRows);
    MakePartPriceGenerationServiceImpl generationService = buildGenerationService(calcRowMapper);

    MakePartPriceGenerateResponse response =
        generationService.generateByOa(OA_NO, BU, PERIOD);

    assertThat(response.getParentCount()).isEqualTo(4);
    assertThat(response.getRowCount()).isEqualTo(6);
    assertThat(response.getOkCount()).isEqualTo(3);
    assertThat(response.getStatusSummary())
        .containsEntry("OK", 3)
        .containsEntry(MakePartPriceCalculator.STATUS_MISSING_WEIGHT, 1)
        .containsEntry(MakePartPriceCalculator.STATUS_MISSING_RAW_PRICE, 1)
        .containsEntry(MakePartPriceCalculator.STATUS_MISSING_SCRAP_PRICE, 1);

    List<MakePartPriceCalcRow> sampleRows = rowsForParent(generatedRows, SAMPLE_PARENT);
    assertThat(sampleRows).hasSize(3);
    assertThat(sampleRows).extracting(MakePartPriceCalcRow::getChildMaterialNo)
        .containsExactly("RAW-203250582-A", "RAW-203250582-A", "BLANK-203250582-B");
    assertThat(sampleRows).extracting(MakePartPriceCalcRow::getScrapCode)
        .containsExactly("SCRAP-T11-A", "SCRAP-T11-B", "SCRAP-T11-A");

    MakePartPriceCalcRow rawScrapA = rowByChildAndScrap(sampleRows, "RAW-203250582-A", "SCRAP-T11-A");
    MakePartPriceCalcRow rawScrapB = rowByChildAndScrap(sampleRows, "RAW-203250582-A", "SCRAP-T11-B");
    MakePartPriceCalcRow blank = rowByChildAndScrap(sampleRows, "BLANK-203250582-B", "SCRAP-T11-A");
    assertThat(rawScrapA.getItemProcessType()).isEqualTo("原材料加工");
    assertThat(blank.getItemProcessType()).isEqualTo("毛坯加工");
    assertThat(rawScrapA.getCostPrice()).isEqualByComparingTo("4.74450000");
    assertThat(rawScrapB.getCostPrice()).isEqualByComparingTo("6.38600000");
    assertThat(blank.getCostPrice()).isEqualByComparingTo("81.05850000");
    assertThat(rawScrapA.getRemark()).contains("gross_weight_g=80", "scrap_weight_kg=0.025");
    assertThat(blank.getRemark()).contains("scrap_weight_kg=0.025");

    BigDecimal expectedTotal = rawScrapA.getCostPrice()
        .add(rawScrapB.getCostPrice())
        .add(blank.getCostPrice());
    assertThat(expectedTotal).isEqualByComparingTo("92.18900000");
    assertThat(sampleRows).allSatisfy(row ->
        assertThat(row.getParentTotalCostPrice()).isEqualByComparingTo(expectedTotal));

    MakePartPriceCalcRow missingWeight = onlyRow(rowsForParent(generatedRows, "MAKE-MISSING-WEIGHT"));
    assertThat(missingWeight.getStatus()).isEqualTo(MakePartPriceCalculator.STATUS_MISSING_WEIGHT);
    assertThat(missingWeight.getRemark()).contains("缺重量");
    MakePartPriceCalcRow missingRoute = onlyRow(rowsForParent(generatedRows, "MAKE-MISSING-ROUTE"));
    assertThat(missingRoute.getStatus()).isEqualTo(MakePartPriceCalculator.STATUS_MISSING_RAW_PRICE);
    assertThat(missingRoute.getRemark()).contains("MISSING_ROUTE");
    MakePartPriceCalcRow missingScrapPrice =
        onlyRow(rowsForParent(generatedRows, "MAKE-MISSING-SCRAP-PRICE"));
    assertThat(missingScrapPrice.getStatus())
        .isEqualTo(MakePartPriceCalculator.STATUS_MISSING_SCRAP_PRICE);
    assertThat(missingScrapPrice.getRemark()).contains("缺回收价格");

    CostRunPartItemServiceImpl costRunService = buildCostRunService(calcRowMapper);
    List<CostRunPartItemDto> costItems = costRunService.listByOaNo(OA_NO);

    assertThat(costItems).hasSize(1);
    CostRunPartItemDto makeItem = costItems.get(0);
    assertThat(makeItem.getPartCode()).isEqualTo(SAMPLE_PARENT);
    assertThat(makeItem.getPriceSource()).isEqualTo(PriceTypeEnum.MAKE.getDbText());
    assertThat(makeItem.getUnitPrice()).isEqualByComparingTo("92.18900000");
    assertThat(makeItem.getAmount()).isEqualByComparingTo("184.37800000");
    assertThat(makeItem.getRemark())
        .contains("取自制造件价格生成结果")
        .contains("批次=")
        .contains("价格月份=" + YearMonth.now());
  }

  @Test
  @DisplayName("MPPG-11：新链路不读取旧 lp_price_scrap")
  void newLinkDoesNotReferenceDeletedScrapPriceTable() throws Exception {
    List<Path> sourceFiles = List.of(
        Path.of("src/main/java/com/sanhua/marketingcost/service/impl/MakePartPriceGenerationServiceImpl.java"),
        Path.of("src/main/java/com/sanhua/marketingcost/service/MakePartPriceCalculator.java"),
        Path.of("src/main/java/com/sanhua/marketingcost/service/impl/MakePartScrapMappingServiceImpl.java"));
    for (Path sourceFile : sourceFiles) {
      String content = Files.readString(sourceFile, StandardCharsets.UTF_8);
      assertThat(content).doesNotContain("lp_price_scrap");
      assertThat(content).doesNotContain("PriceScrap");
    }
  }

  private MakePartPriceGenerationServiceImpl buildGenerationService(
      MakePartPriceCalcRowMapper calcRowMapper) {
    MakePartSourceDataService sourceDataService = mock(MakePartSourceDataService.class);
    MakePartWeightService weightService = mock(MakePartWeightService.class);
    MakePartScrapMappingService scrapMappingService = mock(MakePartScrapMappingService.class);
    MakePartMaterialPriceResolveService priceResolveService =
        mock(MakePartMaterialPriceResolveService.class);

    when(sourceDataService.listManufacturedParents(OA_NO, BU, null))
        .thenReturn(List.of(
            parent(SAMPLE_PARENT),
            parent("MAKE-MISSING-WEIGHT"),
            parent("MAKE-MISSING-ROUTE"),
            parent("MAKE-MISSING-SCRAP-PRICE")));
    when(sourceDataService.listDedupedChildren(SAMPLE_PARENT))
        .thenReturn(List.of(rawChild(SAMPLE_PARENT, "RAW-203250582-A"), blankChild(SAMPLE_PARENT)));
    when(sourceDataService.listDedupedChildren("MAKE-MISSING-WEIGHT"))
        .thenReturn(List.of(rawChild("MAKE-MISSING-WEIGHT", "RAW-MISSING-WEIGHT")));
    when(sourceDataService.listDedupedChildren("MAKE-MISSING-ROUTE"))
        .thenReturn(List.of(rawChild("MAKE-MISSING-ROUTE", "RAW-MISSING-ROUTE")));
    when(sourceDataService.listDedupedChildren("MAKE-MISSING-SCRAP-PRICE"))
        .thenReturn(List.of(rawChild("MAKE-MISSING-SCRAP-PRICE", "RAW-MISSING-SCRAP-PRICE")));

    when(weightService.resolveWeights(anyString(), any(BomU9Source.class), anyString()))
        .thenAnswer(invocation -> {
          String parentCode = invocation.getArgument(0);
          BomU9Source child = invocation.getArgument(1);
          String processType = invocation.getArgument(2);
          if ("MAKE-MISSING-WEIGHT".equals(parentCode)) {
            return MakePartWeightResult.of(
                parentCode, child.getChildMaterialNo(), processType, null, null, "OK", "");
          }
          return MakePartWeightResult.of(
              parentCode,
              child.getChildMaterialNo(),
              processType,
              new BigDecimal("80"),
              new BigDecimal("55"),
              "OK",
              "");
        });

    when(scrapMappingService.listMappings("RAW-203250582-A", BU))
        .thenReturn(List.of(scrap("SCRAP-T11-A"), scrap("SCRAP-T11-B")));
    when(scrapMappingService.listMappings("BLANK-203250582-B", BU))
        .thenReturn(List.of(scrap("SCRAP-T11-A")));
    when(scrapMappingService.listMappings("RAW-MISSING-WEIGHT", BU))
        .thenReturn(List.of(scrap("SCRAP-T11-A")));
    when(scrapMappingService.listMappings("RAW-MISSING-ROUTE", BU))
        .thenReturn(List.of(scrap("SCRAP-T11-A")));
    when(scrapMappingService.listMappings("RAW-MISSING-SCRAP-PRICE", BU))
        .thenReturn(List.of(scrap("SCRAP-MISSING-PRICE")));

    when(priceResolveService.resolveMaterialUnitPrice(anyString(), eq(PERIOD), any(), any(), eq(OA_NO), eq(BU)))
        .thenAnswer(invocation -> priceFor(invocation.getArgument(0)));

    return new MakePartPriceGenerationServiceImpl(
        sourceDataService,
        new MakePartProcessTypePolicy(),
        weightService,
        scrapMappingService,
        mock(MaterialPriceRouterService.class),
        mock(LinkedPriceEnsureService.class),
        priceResolveService,
        new MakePartPriceCalculator(),
        mock(com.sanhua.marketingcost.service.MakePartNoScrapConfirmationService.class),
        calcRowMapper,
        mock(MakePartPriceGapItemMapper.class));
  }

  private CostRunPartItemServiceImpl buildCostRunService(MakePartPriceCalcRowMapper calcRowMapper) {
    CostRunPartItemMapper partMapper = mock(CostRunPartItemMapper.class);
    MaterialPriceRouterService routerService = mock(MaterialPriceRouterService.class);
    OaFormMapper oaFormMapper = mock(OaFormMapper.class);
    LocalDate currentDate = LocalDate.now();
    OaForm form = new OaForm();
    form.setApplyDate(currentDate);
    when(oaFormMapper.selectOne(any(Wrapper.class))).thenReturn(form);
    when(partMapper.selectBaseByOaNo(OA_NO))
        .thenReturn(List.of(costRunPart(SAMPLE_PARENT, "2")));
    when(routerService.listCandidates(eq(SAMPLE_PARENT), eq(PERIOD), eq(currentDate)))
        .thenReturn(List.of(new PriceTypeRoute(
            SAMPLE_PARENT,
            MaterialFormAttrEnum.MANUFACTURED,
            PriceTypeEnum.MAKE,
            1,
            LocalDate.of(2026, 1, 1),
            null,
            "manual",
            PriceTypeEnum.MAKE.getDbText())));

    return new CostRunPartItemServiceImpl(
        partMapper,
        routerService,
        mock(PackageComponentIdentifyService.class),
        mock(PackageComponentPriceService.class),
        oaFormMapper,
        mock(MaterialMasterMapper.class),
        mock(MaterialMasterRawMapper.class),
        mock(BomRawHierarchyMapper.class),
        List.of(new MakePartPriceCalcResolver(calcRowMapper)));
  }

  private MakePartPriceCalcRowMapper mockCalcRowMapper(List<MakePartPriceCalcRow> generatedRows) {
    MakePartPriceCalcRowMapper calcRowMapper = mock(MakePartPriceCalcRowMapper.class);
    when(calcRowMapper.insert(any(MakePartPriceCalcRow.class)))
        .thenAnswer(invocation -> {
          MakePartPriceCalcRow row = invocation.getArgument(0);
          row.setId((long) generatedRows.size() + 1);
          row.setCreatedAt(LocalDateTime.of(2026, 5, 20, 10, 0).plusSeconds(generatedRows.size()));
          generatedRows.add(row);
          return 1;
        });
    when(calcRowMapper.selectList(any(Wrapper.class)))
        .thenAnswer(invocation -> generatedRows.size() < 6 ? List.of() : latestOkRows(generatedRows));
    Mockito.when(calcRowMapper.selectLatestBatchId(any(), any(), any()))
        .thenAnswer(invocation -> generatedRows.isEmpty() ? null : generatedRows.get(0).getCalcBatchId());
    return calcRowMapper;
  }

  private List<MakePartPriceCalcRow> latestOkRows(List<MakePartPriceCalcRow> generatedRows) {
    return rowsForParent(generatedRows, SAMPLE_PARENT).stream()
        .filter(row -> MakePartPriceCalculator.STATUS_OK.equals(row.getStatus()))
        .sorted(Comparator.comparing(MakePartPriceCalcRow::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(MakePartPriceCalcRow::getId, Comparator.nullsLast(Comparator.naturalOrder()))
            .reversed())
        .toList();
  }

  private List<MakePartPriceCalcRow> rowsForParent(
      List<MakePartPriceCalcRow> rows, String parentMaterialNo) {
    return rows.stream()
        .filter(row -> parentMaterialNo.equals(row.getParentMaterialNo()))
        .sorted(Comparator.comparing(MakePartPriceCalcRow::getId))
        .toList();
  }

  private MakePartPriceCalcRow rowByChildAndScrap(
      List<MakePartPriceCalcRow> rows, String childMaterialNo, String scrapCode) {
    return rows.stream()
        .filter(row -> childMaterialNo.equals(row.getChildMaterialNo()))
        .filter(row -> scrapCode.equals(row.getScrapCode()))
        .findFirst()
        .orElseThrow();
  }

  private MakePartPriceCalcRow onlyRow(List<MakePartPriceCalcRow> rows) {
    assertThat(rows).hasSize(1);
    return rows.get(0);
  }

  private BomCostingRow parent(String code) {
    BomCostingRow row = new BomCostingRow();
    row.setOaNo(OA_NO);
    row.setBusinessUnitType(BU);
    row.setMaterialCode(code);
    row.setMaterialName("制造件" + code);
    row.setMaterialSpec("DRW-" + code);
    row.setShapeAttr("制造件");
    return row;
  }

  private BomU9Source rawChild(String parentCode, String childCode) {
    BomU9Source row = new BomU9Source();
    row.setParentMaterialNo(parentCode);
    row.setChildMaterialNo(childCode);
    row.setChildMaterialName("原材料" + childCode);
    row.setChildMaterialSpec("SPEC-" + childCode);
    row.setStockUnit("kg");
    row.setQtyPerParent(new BigDecimal("0.080"));
    return row;
  }

  private BomU9Source blankChild(String parentCode) {
    BomU9Source row = rawChild(parentCode, "BLANK-203250582-B");
    row.setStockUnit("只");
    return row;
  }

  private MaterialScrapRef scrap(String scrapCode) {
    MaterialScrapRef row = new MaterialScrapRef();
    row.setMaterialCode("RAW");
    row.setScrapCode(scrapCode);
    row.setScrapName("废料" + scrapCode);
    return row;
  }

  private MakePartMaterialPriceResolveResult priceFor(String materialCode) {
    if ("RAW-MISSING-ROUTE".equals(materialCode)) {
      return MakePartMaterialPriceResolveResult.missingRoute(materialCode, "缺价格路由");
    }
    if ("SCRAP-MISSING-PRICE".equals(materialCode)) {
      return MakePartMaterialPriceResolveResult.miss(
          materialCode, "MISSING_PRICE", "缺回收价格", null);
    }
    if ("SCRAP-T11-A".equals(materialCode)) {
      return MakePartMaterialPriceResolveResult.ok(
          materialCode, "固定价", new BigDecimal("75.66"), "", "cms-material-scrap-ref");
    }
    if ("SCRAP-T11-B".equals(materialCode)) {
      return MakePartMaterialPriceResolveResult.ok(
          materialCode, "固定价", new BigDecimal("10.00"), "", "cms-material-scrap-ref");
    }
    return MakePartMaterialPriceResolveResult.ok(
        materialCode, "固定价", new BigDecimal("82.95"), "", "finance-base-price");
  }

  private CostRunPartItemDto costRunPart(String code, String partQty) {
    CostRunPartItemDto dto = new CostRunPartItemDto();
    dto.setOaNo(OA_NO);
    dto.setProductCode("PRODUCT-E2E");
    dto.setPartCode(code);
    dto.setPartName("样例制造件");
    dto.setPartQty(new BigDecimal(partQty));
    return dto;
  }

  private static void setBusinessUnit(String businessUnitType) {
    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken("tester", "N/A", List.of());
    auth.setDetails(Map.of(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, businessUnitType));
    SecurityContextHolder.getContext().setAuthentication(auth);
  }
}
