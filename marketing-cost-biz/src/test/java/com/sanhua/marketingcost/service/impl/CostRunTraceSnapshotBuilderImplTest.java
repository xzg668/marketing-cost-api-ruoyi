package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.CostRunCostItem;
import com.sanhua.marketingcost.entity.CostRunPartItem;
import com.sanhua.marketingcost.entity.CostRunTraceSnapshot;
import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import com.sanhua.marketingcost.entity.PriceFixedItem;
import com.sanhua.marketingcost.entity.PriceLinkedCalcItem;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.entity.PriceRangeItem;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.mapper.CostRunCostItemMapper;
import com.sanhua.marketingcost.mapper.CostRunPartItemMapper;
import com.sanhua.marketingcost.mapper.MakePartPriceCalcRowMapper;
import com.sanhua.marketingcost.mapper.PriceFixedItemMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedCalcItemMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import com.sanhua.marketingcost.mapper.PricePrepareItemMapper;
import com.sanhua.marketingcost.mapper.PriceRangeItemMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("成本核算底稿快照构建器")
class CostRunTraceSnapshotBuilderImplTest {

  @Test
  @DisplayName("按 costRunNo 构建部品、费用和总成本底稿")
  void buildsSnapshotsFromVersionedRows() {
    CostRunPartItemMapper partMapper = mock(CostRunPartItemMapper.class);
    CostRunCostItemMapper costMapper = mock(CostRunCostItemMapper.class);
    PricePrepareItemMapper prepareMapper = mock(PricePrepareItemMapper.class);
    MakePartPriceCalcRowMapper makePartMapper = mock(MakePartPriceCalcRowMapper.class);
    PriceLinkedCalcItemMapper linkedCalcMapper = mock(PriceLinkedCalcItemMapper.class);
    PriceLinkedItemMapper linkedItemMapper = mock(PriceLinkedItemMapper.class);
    PriceFixedItemMapper fixedMapper = mock(PriceFixedItemMapper.class);
    PriceRangeItemMapper rangeMapper = mock(PriceRangeItemMapper.class);
    CostRunTraceSnapshotBuilderImpl builder =
        new CostRunTraceSnapshotBuilderImpl(
            partMapper, costMapper, prepareMapper, makePartMapper, linkedCalcMapper, linkedItemMapper,
            fixedMapper, rangeMapper,
            new ObjectMapper());
    CostRunPartItem fixedPart = part(11L, 101L, "固定采购价", "MAT-FIX", "固定件");
    CostRunPartItem linkedPart = part(12L, 102L, "联动价", "MAT-LINK", "联动件");
    linkedPart.setUnitPrice(new BigDecimal("4.000264"));
    linkedPart.setAmount(new BigDecimal("8.000528"));
    CostRunPartItem makePart = part(13L, 103L, "自制件价格生成", "MAT-MAKE", "自制件");
    when(partMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
        fixedPart,
        linkedPart,
        makePart));
    when(costMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
        cost(21L, 1, "MATERIAL", "材料费", null, null, "30"),
        cost(22L, 2, "LOSS", "净损失率", "30", "0.02", "0.6"),
        cost(23L, 3, "TOTAL", "不含税总成本", null, null, "30.6")));
    when(prepareMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
        prepare(101L, "FIXED", "固定采购价", 9001L),
        prepare(102L, "LINKED", "联动价", 9002L),
        prepare(103L, "MAKE", "自制件价格生成", 9003L)));
    MakePartPriceCalcRow anchor = makeRow(9003L, "BATCH-MAKE-1");
    MakePartPriceCalcRow child = makeRow(9004L, "BATCH-MAKE-1");
    when(makePartMapper.selectById(9003L)).thenReturn(anchor);
    when(makePartMapper.selectList(any(Wrapper.class))).thenReturn(List.of(child));
    when(linkedCalcMapper.selectById(9002L)).thenReturn(linkedCalc());
    when(linkedItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(linkedItem()));
    when(fixedMapper.selectById(9001L)).thenReturn(fixedItem());

    List<CostRunTraceSnapshot> snapshots = builder.build(version());

    assertThat(snapshots).hasSize(6);
    assertThat(snapshots)
        .extracting(CostRunTraceSnapshot::getTraceKey)
        .contains("PART:11", "PART:12", "PART:13", "COST:MATERIAL", "COST:LOSS", "TOTAL");
    assertThat(snapshots)
        .filteredOn(row -> "PART:11".equals(row.getTraceKey()))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.getTraceType()).isEqualTo("PART_PRICE");
          assertThat(row.getSourceType()).isEqualTo("FIXED_PRICE");
          assertThat(row.getSourceRefId()).isEqualTo(9001L);
          assertThat(row.getFormulaSnapshotJson()).contains("固定采购价金额", "lp_price_fixed_item");
          assertThat(row.getVariablesJson()).contains("固定采购价", "固定供应商", "3");
        });
    assertThat(snapshots)
        .filteredOn(row -> "PART:12".equals(row.getTraceKey()))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.getSourceType()).isEqualTo("LINKED_PRICE");
          assertThat(row.getSourceRefId()).isEqualTo(9002L);
          assertThat(row.getFormulaSnapshotJson())
              .contains(
                  "([factor_identity_191] + [process_fee])",
                  "1#Cu",
                  "加工费",
                  "83.53982300884955",
                  "4.000264",
                  "8.000528");
          assertThat(row.getVariablesJson())
              .contains(
                  "factor_identity_191",
                  "1#Cu",
                  "90",
                  "process_fee",
                  "加工费",
                  "4.4",
                  "83.53982300884955");
          assertThat(row.getSourceSnapshotJson())
              .contains("联动供应商", "SUP-LINK", "2026-06-01", "2026-06-30");
          assertThat(row.getStepsJson())
              .contains("LINKED_FORMULA", "LINKED_VARIABLES", "LINKED_EVALUATE", "PART_AMOUNT");
        });
    assertThat(snapshots)
        .filteredOn(row -> "PART:13".equals(row.getTraceKey()))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.getSourceType()).isEqualTo("MAKE_PART");
          assertThat(row.getChildrenJson())
              .contains(
                  "301220018",
                  "不锈钢棒",
                  "原材料加工",
                  "5.1",
                  "1.1",
                  "27.256637",
                  "7.2566",
                  "明细行成本 = 毛重",
                  "RAW_MATERIAL_AMOUNT",
                  "SCRAP_DEDUCTION",
                  "0.10998245")
              .doesNotContain("PENDING_DETAIL");
        });
    assertThat(snapshots)
        .filteredOn(row -> "COST:LOSS".equals(row.getTraceKey()))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.getTraceType()).isEqualTo("COST_ITEM");
          assertThat(row.getSourceType()).isEqualTo("RATE_CONFIG");
          assertThat(row.getFormulaSnapshotJson()).contains("净损失 = 损失基数");
          assertThat(row.getBaseAmount()).isEqualByComparingTo("30");
          assertThat(row.getRate()).isEqualByComparingTo("0.02");
        });
    assertThat(snapshots)
        .filteredOn(row -> "TOTAL".equals(row.getTraceKey()))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.getTraceType()).isEqualTo("TOTAL");
          assertThat(row.getChildrenJson()).contains("MATERIAL", "LOSS");
        });
  }

  @Test
  @DisplayName("费用项底稿覆盖重点费用公式、来源和汇总组成项")
  void buildsCostItemTraceForExpenseCodes() {
    CostRunPartItemMapper partMapper = mock(CostRunPartItemMapper.class);
    CostRunCostItemMapper costMapper = mock(CostRunCostItemMapper.class);
    PricePrepareItemMapper prepareMapper = mock(PricePrepareItemMapper.class);
    MakePartPriceCalcRowMapper makePartMapper = mock(MakePartPriceCalcRowMapper.class);
    PriceLinkedCalcItemMapper linkedCalcMapper = mock(PriceLinkedCalcItemMapper.class);
    PriceLinkedItemMapper linkedItemMapper = mock(PriceLinkedItemMapper.class);
    PriceFixedItemMapper fixedMapper = mock(PriceFixedItemMapper.class);
    PriceRangeItemMapper rangeMapper = mock(PriceRangeItemMapper.class);
    CostRunTraceSnapshotBuilderImpl builder =
        new CostRunTraceSnapshotBuilderImpl(
            partMapper, costMapper, prepareMapper, makePartMapper, linkedCalcMapper, linkedItemMapper,
            fixedMapper, rangeMapper,
            new ObjectMapper());

    when(partMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(costMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
        cost(31L, 1, "MATERIAL", "材料费", null, null, "100.000000"),
        sourcedCost(32L, 2, "DIRECT_LABOR", "直接人工工资", null, null, "10.000000",
            "lp_cms_cost_source_effective", 5001L, "CMS直接人工命中"),
        sourcedCost(33L, 3, "INDIRECT_LABOR", "辅助人工工资", null, null, "5.000000",
            "lp_cms_cost_source_effective", 5002L, "CMS辅助人工命中"),
        sourcedCost(34L, 4, "LOSS", "净损失率", "115.000000", "0.020000", "2.300000",
            "lp_quality_loss_rate", 6001L, null),
        sourcedCost(35L, 5, "MANUFACTURE", "制造费用", "128.000000", "0.100000", "12.800000",
            "lp_manufacture_rate", 6002L, null),
        cost(36L, 6, "MANUFACTURE_COST", "制造成本", null, null, "128.000000"),
        sourcedCost(37L, 7, "ADJUSTED_MANUFACTURE_COST", "调整后制造成本",
            "128.000000", "1.100000", "140.800000", "lp_product_property", 7001L, null),
        sourcedCost(38L, 8, "MGMT_EXP", "管理费用", "140.800000", "0.050000", "7.040000",
            "lp_three_expense_rate", 8001L, null),
        sourcedCost(39L, 9, "SALES_EXP", "销售费用", "140.800000", "0.060000", "8.448000",
            "lp_three_expense_rate", 8001L, null),
        sourcedCost(40L, 10, "FIN_EXP", "财务费用", "140.800000", "0.010000", "1.408000",
            "lp_three_expense_rate", 8001L, null),
        sourcedCost(41L, 11, "OVERHAUL", "大修费", "15.000000", "0.004000", "0.060000",
            "lp_department_fund_rate", 9001L, null),
        sourcedCost(42L, 12, "TOOLING_REPAIR", "工装零星修理费", "15.000000", "0.030000", "0.450000",
            "lp_department_fund_rate", 9002L, null),
        sourcedCost(43L, 13, "WATER_POWER", "水电费", "15.000000", "0.040000", "0.600000",
            "lp_department_fund_rate", 9003L, null),
        sourcedCost(44L, 14, "DEPT_OTHER", "其他费用", null, null, "0.000000",
            "lp_department_fund_rate", null, "部门经费率未命中：缺少 production_division"),
        cost(45L, 15, "OTHER_EXP_PACKAGE", "包装费", "20.000000", null, "21.000000"),
        sourcedCost(46L, 16, "OTHER_EXP_FREIGHT", "运费", null, null, "3.000000",
            "oa_form_item", 9501L, null),
        cost(47L, 17, "TOTAL", "不含税总成本", null, null, "160.696000")));

    List<CostRunTraceSnapshot> snapshots = builder.build(version());

    assertThat(snapshots).hasSize(17);
    assertThat(snapshots)
        .extracting(CostRunTraceSnapshot::getTraceKey)
        .contains(
            "COST:MATERIAL",
            "COST:DIRECT_LABOR",
            "COST:INDIRECT_LABOR",
            "COST:LOSS",
            "COST:MANUFACTURE",
            "COST:MANUFACTURE_COST",
            "COST:ADJUSTED_MANUFACTURE_COST",
            "COST:MGMT_EXP",
            "COST:SALES_EXP",
            "COST:FIN_EXP",
            "COST:OVERHAUL",
            "COST:TOOLING_REPAIR",
            "COST:WATER_POWER",
            "COST:DEPT_OTHER",
            "COST:OTHER_EXP_PACKAGE",
            "COST:OTHER_EXP_FREIGHT",
            "TOTAL");
    assertThat(snapshot(snapshots, "COST:DIRECT_LABOR"))
        .satisfies(row -> {
          assertThat(row.getSourceType()).isEqualTo("CMS");
          assertThat(row.getFormulaSnapshotJson()).contains("cms.directLaborAmount");
          assertThat(row.getSourceSnapshotJson())
              .contains("lp_cms_cost_source_effective", "5001", "CMS直接人工工资有效来源");
          assertThat(row.getVariablesJson()).contains("CMS直接人工工资有效来源", "CMS直接人工命中");
        });
    assertThat(snapshot(snapshots, "COST:MGMT_EXP"))
        .satisfies(row -> {
          assertThat(row.getSourceType()).isEqualTo("RATE_CONFIG");
          assertThat(row.getFormulaSnapshotJson()).contains("管理费用 = 调整后制造成本");
          assertThat(row.getVariablesJson()).contains("140.800000", "0.050000", "7.040000");
          assertThat(row.getSourceSnapshotJson()).contains("lp_three_expense_rate", "8001", "三项费用率配置");
        });
    assertThat(snapshot(snapshots, "COST:DEPT_OTHER"))
        .satisfies(row -> {
          assertThat(row.getSourceType()).isEqualTo("CMS");
          assertThat(row.getFormulaSnapshotJson()).contains("其他费用 = CMS部门经费基数");
          assertThat(row.getSourceSnapshotJson())
              .contains("CMS部门经费率有效来源", "部门经费率未命中");
          assertThat(row.getStepsJson()).contains("部门经费率未命中：缺少 production_division");
        });
    assertThat(snapshot(snapshots, "COST:MANUFACTURE_COST").getChildrenJson())
        .contains(
            "MANUFACTURE_COST_COMPONENT",
            "MATERIAL",
            "DIRECT_LABOR",
            "INDIRECT_LABOR",
            "LOSS",
            "MANUFACTURE");
    assertThat(snapshot(snapshots, "COST:ADJUSTED_MANUFACTURE_COST"))
        .satisfies(row -> {
          assertThat(row.getFormulaSnapshotJson()).contains("产品属性系数");
          assertThat(row.getChildrenJson()).contains("ADJUSTED_BASE", "MANUFACTURE_COST");
        });
    assertThat(snapshot(snapshots, "TOTAL").getChildrenJson())
        .contains(
            "TOTAL_COMPONENT",
            "ADJUSTED_MANUFACTURE_COST",
            "MGMT_EXP",
            "SALES_EXP",
            "FIN_EXP",
            "\"included\":true",
            "MATERIAL",
            "OTHER_EXP_PACKAGE",
            "OTHER_EXP_FREIGHT",
            "\"included\":false");
    assertThat(snapshot(snapshots, "TOTAL").getChildrenJson())
        .contains(
            "\"costItemId\":45,\"costCode\":\"OTHER_EXP_PACKAGE\"",
            "\"included\":false",
            "\"costItemId\":46,\"costCode\":\"OTHER_EXP_FREIGHT\"",
            "\"included\":true");
  }

  private QuoteCostRunVersion version() {
    QuoteCostRunVersion version = new QuoteCostRunVersion();
    version.setId(1001L);
    version.setCostRunNo("RUN-T3");
    version.setVersionNo("COST-V1");
    version.setOaNo("OA-1");
    version.setOaFormItemId(501L);
    version.setProductCode("P-1");
    version.setPricingMonth("2026-06");
    version.setBusinessUnitType("COMMERCIAL");
    return version;
  }

  private CostRunPartItem part(
      Long id, Long prepareItemId, String priceSource, String partCode, String partName) {
    CostRunPartItem item = new CostRunPartItem();
    item.setId(id);
    item.setCostRunNo("RUN-T3");
    item.setCostRunVersionId(1001L);
    item.setOaNo("OA-1");
    item.setOaFormItemId(501L);
    item.setProductCode("P-1");
    item.setBomRowId(700L + id);
    item.setPricePrepareItemId(prepareItemId);
    item.setPartCode(partCode);
    item.setPartName(partName);
    item.setPriceSource(priceSource);
    item.setQty(new BigDecimal("2"));
    item.setUnitPrice(new BigDecimal("3"));
    item.setAmount(new BigDecimal("6"));
    item.setBusinessUnitType("COMMERCIAL");
    return item;
  }

  private PricePrepareItem prepare(Long id, String resultRefType, String priceSource, Long resultRefId) {
    PricePrepareItem item = new PricePrepareItem();
    item.setId(id);
    item.setPrepareNo("PPR-T3");
    item.setPeriodMonth("2026-06");
    item.setMaterialCode("MAT-" + id);
    item.setPriceSource(priceSource);
    item.setResultRefType(resultRefType);
    item.setResultRefId(resultRefId);
    item.setUnitPrice(new BigDecimal("3"));
    item.setAmount(new BigDecimal("6"));
    item.setStatus("READY");
    return item;
  }

  private MakePartPriceCalcRow makeRow(Long id, String calcBatchId) {
    MakePartPriceCalcRow row = new MakePartPriceCalcRow();
    row.setId(id);
    row.setCalcBatchId(calcBatchId);
    row.setOaNo("OA-1");
    row.setBusinessUnitType("COMMERCIAL");
    row.setPricingMonth("2026-06");
    row.setParentMaterialNo("MAT-MAKE");
    row.setParentMaterialName("阀针");
    row.setDrawingNo("201290407");
    row.setItemProcessType("原材料加工");
    row.setChildMaterialNo("301220018");
    row.setChildMaterialName("不锈钢棒");
    row.setChildMaterialSpec("SUS");
    row.setStockUnit("kg");
    row.setQtyPerParent(new BigDecimal("1"));
    row.setGrossWeightG(new BigDecimal("5.1"));
    row.setNetWeightG(new BigDecimal("1.1"));
    row.setRawPriceType("联动价");
    row.setRawUnitPrice(new BigDecimal("27.256637"));
    row.setScrapCode("SCRAP-SS");
    row.setScrapName("不锈钢废料");
    row.setScrapPriceType("固定价");
    row.setScrapUnitPrice(new BigDecimal("7.2566"));
    row.setNoScrapConfirmed(false);
    row.setOutsourceFee(BigDecimal.ZERO);
    row.setCostPrice(new BigDecimal("0.10998245"));
    row.setParentTotalCostPrice(new BigDecimal("0.10998245"));
    row.setPriceComplete(true);
    row.setStatus("OK");
    row.setRemark("计算追溯");
    row.setCreatedAt(LocalDateTime.of(2026, 6, 18, 10, 0));
    return row;
  }

  private PriceFixedItem fixedItem() {
    PriceFixedItem item = new PriceFixedItem();
    item.setId(9001L);
    item.setSourceType("PURCHASE_FIXED");
    item.setSourceSystem("SRM");
    item.setSourceName("固定价格表");
    item.setSupplierName("固定供应商");
    item.setSupplierCode("SUP-FIX");
    item.setMaterialCode("MAT-FIX");
    item.setMaterialName("固定件");
    item.setSpecModel("FIX-SPEC");
    item.setUnit("PCS");
    item.setFixedPrice(new BigDecimal("3"));
    item.setCurrentTaxExcludedPrice(new BigDecimal("3"));
    item.setEffectiveFrom(LocalDate.of(2026, 6, 1));
    item.setEffectiveTo(LocalDate.of(2026, 6, 30));
    item.setBusinessUnitType("COMMERCIAL");
    return item;
  }

  private PriceLinkedCalcItem linkedCalc() {
    PriceLinkedCalcItem item = new PriceLinkedCalcItem();
    item.setId(9002L);
    item.setOaNo("OA-1");
    item.setItemCode("MAT-LINK");
    item.setShapeAttr("采购件");
    item.setBomQty(new BigDecimal("2"));
    item.setPartUnitPrice(new BigDecimal("4.000264"));
    item.setPartAmount(new BigDecimal("8.000528"));
    item.setBusinessUnitType("COMMERCIAL");
    item.setCalcScene("QUOTE");
    item.setPricingMonth("2026-06");
    item.setFactorSource("OA_LOCKED");
    item.setCalcStatus("OK");
    item.setTraceJson("""
        {
          "rawExpr":"([factor_identity_191] + [process_fee])",
          "normalizedExpr":"([factor_identity_191] + [process_fee])",
          "variables":{"factor_identity_191":90,"process_fee":4.4},
          "variableDetails":[
            {"code":"factor_identity_191","name":"1#Cu","value":90,"source":"FINANCE_FACTOR"},
            {"code":"process_fee","name":"加工费","value":4.4,"source":"PART_CONTEXT"}
          ],
          "result":83.53982300884955
        }
        """);
    item.setCreatedAt(LocalDateTime.of(2026, 6, 18, 9, 0));
    item.setUpdatedAt(LocalDateTime.of(2026, 6, 18, 9, 30));
    return item;
  }

  private PriceLinkedItem linkedItem() {
    PriceLinkedItem item = new PriceLinkedItem();
    item.setId(8002L);
    item.setPricingMonth("2026-06");
    item.setBusinessUnitType("COMMERCIAL");
    item.setSourceName("Excel导入");
    item.setSupplierName("联动供应商");
    item.setSupplierCode("SUP-LINK");
    item.setMaterialName("联动件");
    item.setMaterialCode("MAT-LINK");
    item.setFormulaExpr("([factor_identity_191] + [process_fee])");
    item.setFormulaExprCn("(1#Cu + 加工费)");
    item.setProcessFee(new BigDecimal("4.4"));
    item.setManualPrice(new BigDecimal("4.000264"));
    item.setEffectiveFrom(LocalDate.of(2026, 6, 1));
    item.setEffectiveTo(LocalDate.of(2026, 6, 30));
    item.setDeleted(0);
    return item;
  }

  private CostRunCostItem cost(
      Long id, Integer lineNo, String code, String name, String base, String rate, String amount) {
    CostRunCostItem item = new CostRunCostItem();
    item.setId(id);
    item.setCostRunNo("RUN-T3");
    item.setCostRunVersionId(1001L);
    item.setOaNo("OA-1");
    item.setOaFormItemId(501L);
    item.setProductCode("P-1");
    item.setLineNo(lineNo);
    item.setCostCode(code);
    item.setCostName(name);
    item.setBaseAmount(base == null ? null : new BigDecimal(base));
    item.setRate(rate == null ? null : new BigDecimal(rate));
    item.setAmount(new BigDecimal(amount));
    item.setCategory("EXPENSE");
    item.setBusinessUnitType("COMMERCIAL");
    return item;
  }

  private CostRunCostItem sourcedCost(
      Long id,
      Integer lineNo,
      String code,
      String name,
      String base,
      String rate,
      String amount,
      String sourceTable,
      Long sourceId,
      String remark) {
    CostRunCostItem item = cost(id, lineNo, code, name, base, rate, amount);
    item.setSourceTable(sourceTable);
    item.setSourceId(sourceId);
    item.setRemark(remark);
    return item;
  }

  private CostRunTraceSnapshot snapshot(List<CostRunTraceSnapshot> snapshots, String traceKey) {
    return snapshots.stream()
        .filter(row -> traceKey.equals(row.getTraceKey()))
        .findFirst()
        .orElseThrow();
  }
}
