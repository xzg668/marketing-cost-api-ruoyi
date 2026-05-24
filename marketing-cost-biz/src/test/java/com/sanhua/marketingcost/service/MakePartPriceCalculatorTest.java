package com.sanhua.marketingcost.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MakePartPriceCalculatorTest {

  private final MakePartPriceCalculator calculator = new MakePartPriceCalculator();

  @Test
  @DisplayName("原材料加工公式：gross/1000*raw - (gross-net)/1000*scrap")
  void calculatesRawProcessCost() {
    MakePartPriceCalcRow row = baseRow("SCRAP-001");
    row.setItemProcessType(MakePartProcessTypePolicy.PROCESS_TYPE_RAW);

    MakePartPriceCalcRow result = calculator.calculate(List.of(row)).getFirst();

    assertThat(result.getStatus()).isEqualTo("OK");
    assertThat(result.getPricingMonth()).isEqualTo("2026-05");
    assertThat(result.getPriceComplete()).isTrue();
    assertThat(result.getCostPrice()).isEqualByComparingTo("4.74450000");
    assertThat(result.getParentTotalCostPrice()).isEqualByComparingTo("4.74450000");
    assertThat(result.getRemark()).contains("计算追溯", "gross_weight_g=80", "outsource_fee_ignored=99");
  }

  @Test
  @DisplayName("毛坯加工公式：raw - (gross-net)/1000*scrap，采购单价按元/件")
  void calculatesBlankProcessCost() {
    MakePartPriceCalcRow row = baseRow("SCRAP-001");
    row.setItemProcessType(MakePartProcessTypePolicy.PROCESS_TYPE_BLANK);

    MakePartPriceCalcRow result = calculator.calculate(List.of(row)).getFirst();

    assertThat(result.getStatus()).isEqualTo("OK");
    assertThat(result.getCostPrice()).isEqualByComparingTo("81.05850000");
    assertThat(result.getParentTotalCostPrice()).isEqualByComparingTo("81.05850000");
  }

  @Test
  @DisplayName("多废料：同 parent + child + 两个 scrap_code 生成两行，汇总价等于两行之和")
  void sumsMultipleScrapRowsByParent() {
    MakePartPriceCalcRow first = baseRow("SCRAP-001");
    first.setItemProcessType(MakePartProcessTypePolicy.PROCESS_TYPE_RAW);
    MakePartPriceCalcRow second = baseRow("SCRAP-002");
    second.setItemProcessType(MakePartProcessTypePolicy.PROCESS_TYPE_RAW);
    second.setScrapUnitPrice(new BigDecimal("10.000000"));

    List<MakePartPriceCalcRow> result = calculator.calculate(List.of(first, second));

    assertThat(result).hasSize(2);
    assertThat(result).extracting(MakePartPriceCalcRow::getCostPrice)
        .containsExactly(new BigDecimal("4.74450000"), new BigDecimal("6.38600000"));
    assertThat(result).allSatisfy(row ->
        assertThat(row.getParentTotalCostPrice()).isEqualByComparingTo("11.13050000"));
  }

  @Test
  @DisplayName("缺重量、缺原材料价格、缺回收价格、缺废料映射不计算 OK 价")
  void missingInputsReturnErrorRows() {
    MakePartPriceCalcRow missingWeight = baseRow("SCRAP-WEIGHT");
    missingWeight.setGrossWeightG(null);
    MakePartPriceCalcRow missingRawPrice = baseRow("SCRAP-RAW");
    missingRawPrice.setRawUnitPrice(null);
    MakePartPriceCalcRow missingScrapPrice = baseRow("SCRAP-PRICE");
    missingScrapPrice.setScrapUnitPrice(null);
    MakePartPriceCalcRow missingScrapMapping = baseRow(null);

    List<MakePartPriceCalcRow> result =
        calculator.calculate(List.of(missingWeight, missingRawPrice, missingScrapPrice, missingScrapMapping));

    assertThat(result).extracting(MakePartPriceCalcRow::getStatus)
        .containsExactly(
            "MISSING_WEIGHT",
            "MISSING_RAW_PRICE",
            "MISSING_SCRAP_PRICE",
            "MISSING_SCRAP_MAPPING");
    assertThat(result).extracting(MakePartPriceCalcRow::getPriceComplete)
        .containsExactly(true, false, false, true);
    assertThat(result).allSatisfy(row -> {
      assertThat(row.getCostPrice()).isNull();
      assertThat(row.getParentTotalCostPrice()).isNull();
      assertThat(row.getRemark()).isNotBlank();
    });
  }

  @Test
  @DisplayName("委外加工费第一版不影响 cost_price")
  void outsourceFeeDoesNotAffectCostPrice() {
    MakePartPriceCalcRow withoutOutsource = baseRow("SCRAP-001");
    withoutOutsource.setItemProcessType(MakePartProcessTypePolicy.PROCESS_TYPE_RAW);
    withoutOutsource.setOutsourceFee(BigDecimal.ZERO);
    MakePartPriceCalcRow withOutsource = baseRow("SCRAP-002");
    withOutsource.setItemProcessType(MakePartProcessTypePolicy.PROCESS_TYPE_RAW);
    withOutsource.setOutsourceFee(new BigDecimal("999.000000"));

    List<MakePartPriceCalcRow> result = calculator.calculate(List.of(withoutOutsource, withOutsource));

    assertThat(result).extracting(MakePartPriceCalcRow::getCostPrice)
        .containsExactly(new BigDecimal("4.74450000"), new BigDecimal("4.74450000"));
  }

  @Test
  @DisplayName("上游已有异常状态时保留异常并跳过计算")
  void upstreamErrorStatusSkipsCalculation() {
    MakePartPriceCalcRow row = baseRow("SCRAP-001");
    row.setStatus("MISSING_STOCK_UNIT");
    row.setRemark("stock_unit 为空");

    MakePartPriceCalcRow result = calculator.calculate(List.of(row)).getFirst();

    assertThat(result.getStatus()).isEqualTo("MISSING_STOCK_UNIT");
    assertThat(result.getCostPrice()).isNull();
    assertThat(result.getParentTotalCostPrice()).isNull();
    assertThat(result.getRemark()).contains("stock_unit 为空", "上游异常");
  }

  private MakePartPriceCalcRow baseRow(String scrapCode) {
    MakePartPriceCalcRow row = new MakePartPriceCalcRow();
    row.setPricingMonth("2026-05");
    row.setParentMaterialNo("MAKE-001");
    row.setChildMaterialNo("RAW-001");
    row.setGrossWeightG(new BigDecimal("80"));
    row.setNetWeightG(new BigDecimal("55"));
    row.setRawUnitPrice(new BigDecimal("82.95"));
    row.setScrapCode(scrapCode);
    row.setScrapUnitPrice(new BigDecimal("75.66"));
    row.setOutsourceFee(new BigDecimal("99"));
    row.setItemProcessType(MakePartProcessTypePolicy.PROCESS_TYPE_RAW);
    return row;
  }
}
