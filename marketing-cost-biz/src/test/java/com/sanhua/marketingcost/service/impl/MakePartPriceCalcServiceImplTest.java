package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.MakePartPriceCalcPageResponse;
import com.sanhua.marketingcost.dto.MakePartPriceCalcQueryRequest;
import com.sanhua.marketingcost.dto.MakePartPriceGapPageResponse;
import com.sanhua.marketingcost.dto.MakePartPriceGenerateRequest;
import com.sanhua.marketingcost.dto.MakePartPriceGenerateResponse;
import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import com.sanhua.marketingcost.entity.MakePartPriceGapItem;
import com.sanhua.marketingcost.mapper.MakePartPriceCalcRowMapper;
import com.sanhua.marketingcost.mapper.MakePartPriceGapItemMapper;
import com.sanhua.marketingcost.service.MakePartPriceGenerationService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class MakePartPriceCalcServiceImplTest {

  private MakePartPriceCalcRowMapper mapper;
  private MakePartPriceGapItemMapper gapItemMapper;
  private MakePartPriceGenerationService generationService;
  private MakePartPriceCalcServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), MakePartPriceCalcRow.class);
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), MakePartPriceGapItem.class);
  }

  @BeforeEach
  void setUp() {
    SecurityContextHolder.clearContext();
    mapper = mock(MakePartPriceCalcRowMapper.class);
    gapItemMapper = mock(MakePartPriceGapItemMapper.class);
    generationService = mock(MakePartPriceGenerationService.class);
    service = new MakePartPriceCalcServiceImpl(mapper, gapItemMapper, generationService);
  }

  @Test
  @DisplayName("分页查询条件正确")
  void pageUsesAllFilters() {
    MakePartPriceCalcRow row = row("OK");
    when(mapper.selectPage(any(Page.class), any(Wrapper.class)))
        .thenAnswer(invocation -> {
          Page<MakePartPriceCalcRow> page = invocation.getArgument(0);
          page.setTotal(1);
          page.setRecords(List.of(row));
          return page;
        });
    MakePartPriceCalcQueryRequest request = fullQuery();

    MakePartPriceCalcPageResponse response = service.page(request);

    assertThat(response.getTotal()).isEqualTo(1);
    ArgumentCaptor<Page<MakePartPriceCalcRow>> pageCaptor = ArgumentCaptor.forClass(Page.class);
    ArgumentCaptor<Wrapper<MakePartPriceCalcRow>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
    verify(mapper).selectPage(pageCaptor.capture(), wrapperCaptor.capture());
    assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(2);
    assertThat(pageCaptor.getValue().getSize()).isEqualTo(30);
    String sql = ((AbstractWrapper<?, ?, ?>) wrapperCaptor.getValue()).getSqlSegment();
    assertThat(sql)
        .contains(
            "calc_batch_id",
            "pricing_month",
            "oa_no",
            "business_unit_type",
            "parent_material_no",
            "child_material_no",
            "scrap_code",
            "item_process_type",
            "status");
  }

  @Test
  @DisplayName("缺价清单分页查询条件正确")
  void gapPageUsesAllFilters() {
    MakePartPriceGapItem gap = gapItem("RAW", "RAW-001");
    when(gapItemMapper.selectPage(any(Page.class), any(Wrapper.class)))
        .thenAnswer(invocation -> {
          Page<MakePartPriceGapItem> page = invocation.getArgument(0);
          page.setTotal(1);
          page.setRecords(List.of(gap));
          return page;
        });
    MakePartPriceCalcQueryRequest request = fullQuery();
    request.setMissingPriceRole("RAW");
    request.setMissingMaterialNo("RAW-001");

    MakePartPriceGapPageResponse response = service.gapPage(request);

    assertThat(response.getTotal()).isEqualTo(1);
    ArgumentCaptor<Wrapper<MakePartPriceGapItem>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
    verify(gapItemMapper).selectPage(any(Page.class), wrapperCaptor.capture());
    String sql = ((AbstractWrapper<?, ?, ?>) wrapperCaptor.getValue()).getSqlSegment();
    assertThat(sql)
        .contains(
            "calc_batch_id",
            "pricing_month",
            "oa_no",
            "business_unit_type",
            "parent_material_no",
            "child_material_no",
            "scrap_code",
            "missing_price_role",
            "missing_material_no");
  }

  @Test
  @DisplayName("只看异常过滤使用 status <> OK")
  void onlyErrorFilterUsesNotOk() {
    when(mapper.selectPage(any(Page.class), any(Wrapper.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    MakePartPriceCalcQueryRequest request = new MakePartPriceCalcQueryRequest();
    request.setOnlyError(true);
    request.setStatus("OK");

    service.page(request);

    ArgumentCaptor<Wrapper<MakePartPriceCalcRow>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
    verify(mapper).selectPage(any(Page.class), wrapperCaptor.capture());
    String sql = ((AbstractWrapper<?, ?, ?>) wrapperCaptor.getValue()).getSqlSegment();
    assertThat(sql).contains("status <>").doesNotContain("status =");
  }

  @Test
  @DisplayName("生成接口返回批次统计")
  void generateReturnsBatchStats() {
    MakePartPriceGenerateRequest request = new MakePartPriceGenerateRequest();
    request.setOaNo("OA-001");
    request.setPeriod("2026-05");
    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken("tester", "N/A");
    auth.setDetails(Map.of("businessUnitType", "COMMERCIAL"));
    SecurityContextHolder.getContext().setAuthentication(auth);
    MakePartPriceGenerateResponse expected =
        new MakePartPriceGenerateResponse("MPPG-1", 1, 2, 2, 0, 0);
    expected.setStatusSummary(Map.of("OK", 2));
    when(generationService.generateByOa("OA-001", "COMMERCIAL", "2026-05")).thenReturn(expected);

    MakePartPriceGenerateResponse response = service.generate(request);

    assertThat(response.getCalcBatchId()).isEqualTo("MPPG-1");
    assertThat(response.getStatusSummary()).containsEntry("OK", 2);
  }

  @Test
  @DisplayName("生成请求未传期间时默认使用当前系统月份")
  void generateUsesCurrentMonthWhenPeriodBlank() {
    MakePartPriceGenerateRequest request = new MakePartPriceGenerateRequest();
    request.setOaNo("OA-001");
    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken("tester", "N/A");
    auth.setDetails(Map.of("businessUnitType", "COMMERCIAL"));
    SecurityContextHolder.getContext().setAuthentication(auth);
    MakePartPriceGenerateResponse expected =
        new MakePartPriceGenerateResponse("MPPG-1", 1, 1, 1, 0, 0);
    when(generationService.generateByOa("OA-001", "COMMERCIAL", YearMonth.now().toString()))
        .thenReturn(expected);

    MakePartPriceGenerateResponse response = service.generate(request);

    assertThat(response.getCalcBatchId()).isEqualTo("MPPG-1");
    verify(generationService).generateByOa("OA-001", "COMMERCIAL", YearMonth.now().toString());
  }

  @Test
  @DisplayName("详情能看到计算追溯")
  void detailIncludesCalculationTrace() {
    MakePartPriceCalcRow row = row("OK");
    row.setRemark("计算追溯: gross_weight_g=80");
    when(mapper.selectById(9L)).thenReturn(row);

    MakePartPriceCalcRow result = service.get(9L);

    assertThat(result.getRemark()).contains("计算追溯");
  }

  @Test
  @DisplayName("状态汇总按状态聚合")
  void statusSummaryGroupsRows() {
    when(mapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(row("OK"), row("MISSING_WEIGHT"), row("MISSING_WEIGHT")));

    Map<String, Integer> summary = service.statusSummary(new MakePartPriceCalcQueryRequest());

    assertThat(summary).containsEntry("OK", 1).containsEntry("MISSING_WEIGHT", 2);
  }

  @Test
  @DisplayName("导出 Excel 字段顺序和设计文档一致，且不包含废弃字段")
  void exportHeadersMatchDesignAndExcludeDeprecatedFields() {
    assertThat(service.exportHeaders())
        .containsExactly(
            "价格月份", "生成时间", "OA单号", "料号", "名称", "图号", "料件类型", "毛重(g)", "净重(g)", "零件价格", "原材料代码",
            "原材料/毛坯", "原材料价格", "回收代码", "回收名称", "回收单价", "委外加工费", "是否完整取价", "状态", "备注")
        .doesNotContain("外径", "壁厚", "净长1", "净长2");
  }

  @Test
  @DisplayName("导出写入两个 sheet，缺价清单行数和缺价表一致")
  void exportWritesWorkbookWithGapSheet() throws Exception {
    when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(row("OK")));
    when(gapItemMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(gapItem("RAW", "RAW-001"), gapItem("SCRAP", "SCRAP-001")));
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    int rows = service.export(new MakePartPriceCalcQueryRequest(), output);

    assertThat(rows).isEqualTo(1);
    assertThat(output.size()).isGreaterThan(0);
    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(output.toByteArray()))) {
      assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
      assertThat(workbook.getSheetName(0)).isEqualTo("制造件价格生成");
      assertThat(workbook.getSheetName(1)).isEqualTo("缺价清单");
      assertThat(workbook.getSheetAt(1).getLastRowNum()).isEqualTo(2);
      assertThat(workbook.getSheetAt(1).getRow(0).getCell(5).getStringCellValue())
          .isEqualTo("制造件料号");
      assertThat(workbook.getSheetAt(1).getRow(0).getCell(7).getStringCellValue())
          .isEqualTo("原材料料号");
      assertThat(workbook.getSheetAt(1).getRow(0).getCell(10).getStringCellValue())
          .isEqualTo("废料料号");
      assertThat(workbook.getSheetAt(1).getRow(0).getCell(13).getStringCellValue())
          .isEqualTo("要补价的料号");
    }
  }

  private MakePartPriceCalcQueryRequest fullQuery() {
    MakePartPriceCalcQueryRequest request = new MakePartPriceCalcQueryRequest();
    request.setCalcBatchId("MPPG-1");
    request.setPricingMonth("2026-05");
    request.setOaNo("OA-001");
    request.setBusinessUnitType("COMMERCIAL");
    request.setParentMaterialNo("MAKE-001");
    request.setChildMaterialNo("RAW-001");
    request.setScrapCode("SCRAP-001");
    request.setItemProcessType("原材料加工");
    request.setStatus("OK");
    request.setPage(2);
    request.setPageSize(30);
    return request;
  }

  private MakePartPriceCalcRow row(String status) {
    MakePartPriceCalcRow row = new MakePartPriceCalcRow();
    row.setId(9L);
    row.setCalcBatchId("MPPG-1");
    row.setPricingMonth("2026-05");
    row.setOaNo("OA-001");
    row.setBusinessUnitType("COMMERCIAL");
    row.setParentMaterialNo("MAKE-001");
    row.setParentMaterialName("制造件");
    row.setDrawingNo("DRW");
    row.setItemProcessType("原材料加工");
    row.setGrossWeightG(new BigDecimal("80"));
    row.setNetWeightG(new BigDecimal("55"));
    row.setParentTotalCostPrice(new BigDecimal("4.74450000"));
    row.setPriceComplete(true);
    row.setChildMaterialNo("RAW-001");
    row.setChildMaterialSpec("SPEC");
    row.setRawUnitPrice(new BigDecimal("82.95"));
    row.setScrapCode("SCRAP-001");
    row.setScrapName("废料");
    row.setScrapUnitPrice(new BigDecimal("75.66"));
    row.setOutsourceFee(BigDecimal.ZERO);
    row.setStatus(status);
    row.setRemark("remark");
    row.setCreatedAt(LocalDateTime.of(2026, 5, 20, 10, 0));
    return row;
  }

  private MakePartPriceGapItem gapItem(String role, String missingMaterialNo) {
    MakePartPriceGapItem item = new MakePartPriceGapItem();
    item.setId(19L);
    item.setCalcBatchId("MPPG-1");
    item.setPricingMonth("2026-05");
    item.setGeneratedAt(LocalDateTime.of(2026, 5, 20, 10, 1));
    item.setOaNo("OA-001");
    item.setBusinessUnitType("COMMERCIAL");
    item.setParentMaterialNo("MAKE-001");
    item.setParentMaterialName("制造件");
    item.setChildMaterialNo("RAW-001");
    item.setChildMaterialName("原材料");
    item.setChildMaterialSpec("SPEC");
    item.setScrapCode("SCRAP-001");
    item.setScrapName("废料");
    item.setMissingPriceRole(role);
    item.setMissingMaterialNo(missingMaterialNo);
    item.setMissingMaterialName("RAW".equals(role) ? "原材料" : "废料");
    item.setPriceType("固定价");
    item.setReason(role + " 缺价");
    item.setOaPushStatus("NOT_PUSHED");
    return item;
  }
}
