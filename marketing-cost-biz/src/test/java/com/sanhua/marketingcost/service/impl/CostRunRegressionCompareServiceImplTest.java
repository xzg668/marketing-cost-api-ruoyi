package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.dto.CostRunObjectResult;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.CostRunRegressionCompareReport;
import com.sanhua.marketingcost.dto.CostRunRegressionDifference;
import com.sanhua.marketingcost.dto.CostRunResultDto;
import com.sanhua.marketingcost.entity.CostRunCostItem;
import com.sanhua.marketingcost.entity.CostRunPartItem;
import com.sanhua.marketingcost.entity.CostRunResult;
import com.sanhua.marketingcost.mapper.CostRunCostItemMapper;
import com.sanhua.marketingcost.mapper.CostRunPartItemMapper;
import com.sanhua.marketingcost.mapper.CostRunResultMapper;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CostRunRegressionCompareServiceImplTest {

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, CostRunResult.class);
    TableInfoHelper.initTableInfo(assistant, CostRunPartItem.class);
    TableInfoHelper.initTableInfo(assistant, CostRunCostItem.class);
  }

  @Test
  void compareMatchesWhenAmountsOnlyDifferByScaleAndExpenseCategoryIsImplicit() {
    CostRunRegressionCompareServiceImpl service = service();

    CostRunRegressionCompareReport report =
        service.compare(
            objectResult("100.000000", "100.000000", "100.000000", "EXPENSE"),
            objectResult("100", "100", "100", null));

    assertThat(report.isMatched()).isTrue();
    assertThat(report.getDifferences()).isEmpty();
    assertThat(report.getBaselinePartCount()).isEqualTo(1);
    assertThat(report.getCandidateCostItemCount()).isEqualTo(2);
  }

  @Test
  void compareReportsTotalCostCostItemPartItemAndPriceSourceDifferences() {
    CostRunRegressionCompareServiceImpl service = service();

    CostRunRegressionCompareReport report =
        service.compare(
            objectResult("100.000000", "90.000000", "90.000000", "EXPENSE", "固定价"),
            objectResult("110.000000", "95.000000", "91.000000", null, "联动价"));

    assertThat(report.isMatched()).isFalse();
    assertThat(report.getDifferences())
        .extracting(CostRunRegressionDifference::getSection)
        .contains("RESULT", "COST_ITEM", "PART_ITEM");
    assertThat(report.getDifferences())
        .extracting(CostRunRegressionDifference::getFieldName)
        .contains("totalCost", "amount", "unitPrice", "priceSource");
  }

  @Test
  void compareReportsFrontendCoreFieldDifferences() {
    CostRunRegressionCompareServiceImpl service = service();
    CostRunObjectResult baseline = objectResult("100.000000", "90.000000", "90.000000", "EXPENSE");
    CostRunObjectResult candidate = objectResult("100.000000", "90.000000", "90.000000", "EXPENSE");
    candidate.getContext().setBusinessUnitType("HOUSEHOLD");
    candidate.getResult().setProductName("不同产品名");
    candidate.getResult().setPeriod("2026-04");
    candidate.getPartItems().get(0).setPriceType("联动价");

    CostRunRegressionCompareReport report = service.compare(baseline, candidate);

    assertThat(report.isMatched()).isFalse();
    assertThat(report.getDifferences())
        .extracting(CostRunRegressionDifference::getFieldName)
        .contains("businessUnitType", "productName", "period", "priceType");
  }

  @Test
  void compareOaSnapshotsReportsMissingProductAndRendersMarkdown() {
    CostRunRegressionCompareServiceImpl service = service();
    CostRunObjectResult baselineFirst = objectResult("100.000000", "90.000000", "90.000000", "EXPENSE");
    CostRunObjectResult baselineSecond = objectResult("200.000000", "180.000000", "180.000000", "EXPENSE");
    baselineSecond.getContext().setProductCode("MAT-002");
    baselineSecond.getResult().setProductCode("MAT-002");

    List<CostRunRegressionCompareReport> reports =
        service.compareOaSnapshots(List.of(baselineFirst, baselineSecond), List.of(baselineFirst));

    assertThat(reports).hasSize(2);
    assertThat(reports).extracting(CostRunRegressionCompareReport::getProductCode)
        .containsExactly("1079900000536", "MAT-002");
    assertThat(reports.get(1).isMatched()).isFalse();
    assertThat(reports.get(1).getDifferences())
        .extracting(CostRunRegressionDifference::getFieldName)
        .contains("_row");

    String markdown = service.renderMarkdownReport("T35 OA|报告", reports);
    assertThat(markdown).contains("# T35 OA\\|报告", "| 产品数 | 2 |", "## 差异明细");
  }

  @Test
  void loadStoredSnapshotReadsDailyTablesWithoutRunningEngine() {
    CostRunResultMapper resultMapper = mock(CostRunResultMapper.class);
    CostRunPartItemMapper partItemMapper = mock(CostRunPartItemMapper.class);
    CostRunCostItemMapper costItemMapper = mock(CostRunCostItemMapper.class);
    CostRunRegressionCompareServiceImpl service =
        new CostRunRegressionCompareServiceImpl(resultMapper, partItemMapper, costItemMapper);
    when(resultMapper.selectOne(any())).thenReturn(resultEntity());
    when(partItemMapper.selectList(any())).thenReturn(List.of(partEntity()));
    when(costItemMapper.selectList(any())).thenReturn(List.of(costEntity("TOTAL", "总成本", "130.000000")));

    CostRunObjectResult snapshot =
        service.loadStoredSnapshot("FI-SC-006-20260327-037", "1079900000536");

    assertThat(snapshot.getContext().getScene()).isEqualTo(CostRunContext.SCENE_QUOTE);
    assertThat(snapshot.getResult().getTotalCost()).isEqualByComparingTo("130.000000");
    assertThat(snapshot.getPartItems()).hasSize(1);
    assertThat(snapshot.getCostItems()).hasSize(1);
    verify(resultMapper).selectOne(any());
    verify(partItemMapper).selectList(any());
    verify(costItemMapper).selectList(any());
  }

  @Test
  void loadStoredSnapshotsReadsWholeOaInStableProductOrder() {
    CostRunResultMapper resultMapper = mock(CostRunResultMapper.class);
    CostRunPartItemMapper partItemMapper = mock(CostRunPartItemMapper.class);
    CostRunCostItemMapper costItemMapper = mock(CostRunCostItemMapper.class);
    CostRunRegressionCompareServiceImpl service =
        new CostRunRegressionCompareServiceImpl(resultMapper, partItemMapper, costItemMapper);
    CostRunResult first = resultEntity();
    first.setProductCode("MAT-001");
    CostRunResult second = resultEntity();
    second.setId(8L);
    second.setProductCode("MAT-002");
    when(resultMapper.selectList(any())).thenReturn(List.of(first, second));
    when(resultMapper.selectOne(any()))
        .thenReturn(first)
        .thenReturn(second);
    when(partItemMapper.selectList(any())).thenReturn(List.of(partEntity()));
    when(costItemMapper.selectList(any())).thenReturn(List.of(costEntity("TOTAL", "总成本", "130.000000")));

    List<CostRunObjectResult> snapshots = service.loadStoredSnapshots("FI-SC-006-20260327-037");

    assertThat(snapshots).hasSize(2);
    assertThat(snapshots)
        .extracting(snapshot -> snapshot.getContext().getProductCode())
        .containsExactly("MAT-001", "MAT-002");
    verify(resultMapper).selectList(any());
  }

  private CostRunRegressionCompareServiceImpl service() {
    return new CostRunRegressionCompareServiceImpl(
        mock(CostRunResultMapper.class),
        mock(CostRunPartItemMapper.class),
        mock(CostRunCostItemMapper.class));
  }

  private CostRunObjectResult objectResult(
      String totalCost, String materialAmount, String partAmount, String materialCategory) {
    return objectResult(totalCost, materialAmount, partAmount, materialCategory, "固定价");
  }

  private CostRunObjectResult objectResult(
      String totalCost,
      String materialAmount,
      String partAmount,
      String materialCategory,
      String priceSource) {
    CostRunResultDto result = new CostRunResultDto();
    result.setOaNo("FI-SC-006-20260327-037");
    result.setProductCode("1079900000536");
    result.setProductName("SHF-AA-79 四通阀");
    result.setProductModel("SHF-AA-79");
    result.setCustomerName("客户A");
    result.setBusinessUnit("商用");
    result.setPeriod("2026-03");
    result.setCurrency("CNY");
    result.setUnit("PCS");
    result.setTotalCost(new BigDecimal(totalCost));
    result.setCalcStatus("已核算");
    result.setProductAttr("标准品");
    return CostRunObjectResult.of(
        CostRunContext.quote(
            "FI-SC-006-20260327-037",
            1L,
            "1079900000536",
            "纸箱",
            "客户A",
            "COMMERCIAL",
            "2026-03",
            "FI-SC-006-20260327-037|1079900000536"),
        7L,
        result,
        List.of(partItem(partAmount, priceSource)),
        List.of(
            costItem("MATERIAL", "直接材料费", materialAmount, materialCategory),
            costItem("TOTAL", "总成本", totalCost, materialCategory)));
  }

  private CostRunPartItemDto partItem(String amount, String priceSource) {
    CostRunPartItemDto item = new CostRunPartItemDto();
    item.setOaNo("FI-SC-006-20260327-037");
    item.setProductCode("1079900000536");
    item.setPartCode("PART-001");
    item.setPartName("阀体");
    item.setPartDrawingNo("DRAW-001");
    item.setPartQty(new BigDecimal("2.000000"));
    item.setMaterial("铜");
    item.setShapeAttr("采购件");
    item.setPriceType("固定价");
    item.setPriceSource(priceSource);
    item.setUnitPrice(new BigDecimal(amount).divide(new BigDecimal("2"), 6, java.math.RoundingMode.HALF_UP));
    item.setAmount(new BigDecimal(amount));
    return item;
  }

  private CostRunCostItemDto costItem(String code, String name, String amount, String category) {
    CostRunCostItemDto item = new CostRunCostItemDto();
    item.setCostCode(code);
    item.setCostName(name);
    item.setAmount(new BigDecimal(amount));
    item.setCategory(category);
    return item;
  }

  private CostRunResult resultEntity() {
    CostRunResult result = new CostRunResult();
    result.setId(7L);
    result.setOaNo("FI-SC-006-20260327-037");
    result.setProductCode("1079900000536");
    result.setCustomerName("客户A");
    result.setBusinessUnitType("COMMERCIAL");
    result.setPeriod("2026-03");
    result.setTotalCost(new BigDecimal("130.000000"));
    return result;
  }

  private CostRunPartItem partEntity() {
    CostRunPartItem item = new CostRunPartItem();
    item.setId(1L);
    item.setOaNo("FI-SC-006-20260327-037");
    item.setProductCode("1079900000536");
    item.setPartCode("PART-001");
    item.setPartName("阀体");
    item.setPartDrawingNo("DRAW-001");
    item.setQty(new BigDecimal("2.000000"));
    item.setPriceSource("固定价");
    item.setUnitPrice(new BigDecimal("65.000000"));
    item.setAmount(new BigDecimal("130.000000"));
    return item;
  }

  private CostRunCostItem costEntity(String code, String name, String amount) {
    CostRunCostItem item = new CostRunCostItem();
    item.setId(1L);
    item.setLineNo(1);
    item.setOaNo("FI-SC-006-20260327-037");
    item.setProductCode("1079900000536");
    item.setCostCode(code);
    item.setCostName(name);
    item.setAmount(new BigDecimal(amount));
    item.setCategory("EXPENSE");
    return item;
  }
}
