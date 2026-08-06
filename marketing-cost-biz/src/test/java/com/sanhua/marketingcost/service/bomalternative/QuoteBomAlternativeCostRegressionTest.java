package com.sanhua.marketingcost.service.bomalternative;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.entity.BomCostingRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QBA-13 标准/替代分支价格和成本输入回归")
class QuoteBomAlternativeCostRegressionTest {

  private QuoteBomAlternativeRealDataTestSupport support;

  @BeforeEach
  void setUp() {
    support = new QuoteBomAlternativeRealDataTestSupport();
  }

  @Test
  @DisplayName("默认标准与恢复标准的价格对象、最终单价和总成本完全一致")
  void restoredStandardReturnsToSamePriceAndCostSnapshot() {
    var first = priced(support.defaultStandard().costingRows());
    support.selectAlternative();
    var restored = priced(support.restoreStandard().costingRows());

    assertThat(restored.lines())
        .containsExactlyEntriesOf(first.lines());
    assertThat(restored.totalCost())
        .isEqualByComparingTo(first.totalCost());
  }

  @Test
  @DisplayName("切换替代后共同料号价格成本不变且总差异只来自敏感芯片")
  void costDifferenceOnlyComesFromActuallySelectedMaterial() {
    var standard = priced(support.defaultStandard().costingRows());
    var alternative = priced(support.selectAlternative().costingRows());

    Map<String, PriceLine> standardCommon =
        withoutSensitiveChip(standard.lines());
    Map<String, PriceLine> alternativeCommon =
        withoutSensitiveChip(alternative.lines());
    assertThat(alternativeCommon)
        .containsExactlyEntriesOf(standardCommon);
    assertThat(standard.lines())
        .containsKey("201850547|DEFAULT_LEAF")
        .doesNotContainKey("201850347|DEFAULT_LEAF");
    assertThat(alternative.lines())
        .containsKey("201850347|DEFAULT_LEAF")
        .doesNotContainKey("201850547|DEFAULT_LEAF");

    BigDecimal expectedDifference =
        unitPrice("201850347", "DEFAULT_LEAF")
            .subtract(
                unitPrice("201850547", "DEFAULT_LEAF"));
    assertThat(
            alternative.totalCost()
                .subtract(standard.totalCost()))
        .isEqualByComparingTo(expectedDifference);
  }

  @Test
  @DisplayName("价格类型、废料抵减、包装、自制和委外价格对象仍按原结算行类型生成")
  void specialSettlementRowsKeepExistingPriceSemantics() {
    var priced = priced(support.defaultStandard().costingRows());

    assertThat(priced.lines().values())
        .extracting(PriceLine::priceType)
        .contains(
            "结算固定价",
            "自制件价格",
            "包装组件价格",
            "委外加工价格",
            "废料价格");
    assertThat(priced.lines().values())
        .filteredOn(line -> "废料价格".equals(line.priceType()))
        .hasSize(2)
        .allSatisfy(
            line -> assertThat(line.amount()).isNegative());
    assertThat(priced.lines().get("721850051|SPECIAL_ROLLUP_PARENT"))
        .extracting(PriceLine::priceSource)
        .isEqualTo("自制件生成");
    assertThat(priced.lines().get("9830000025705|PACKAGE_PARENT"))
        .extracting(PriceLine::priceSource)
        .isEqualTo("包装组件价格");
  }

  @Test
  @DisplayName("选择变化后价格类型、价格准备和成本版本继续全部失效")
  void selectionChangeInvalidatesAllDownstreamCostStages() {
    support.defaultStandard();
    var switched = support.selectAlternative();

    assertThat(switched.rebuild().priceTypeInvalidatedCount())
        .isPositive();
    assertThat(switched.rebuild().pricePrepareInvalidatedCount())
        .isPositive();
    assertThat(switched.rebuild().costRunInvalidatedCount())
        .isPositive();
  }

  private static PriceSnapshot priced(
      List<BomCostingRow> rows) {
    Map<String, PriceLine> lines = new LinkedHashMap<>();
    BigDecimal total = BigDecimal.ZERO;
    for (BomCostingRow row : rows) {
      String rowType = row.getSettlementRowType();
      PriceIdentity identity = priceIdentity(rowType);
      BigDecimal finalUnitPrice =
          unitPrice(row.getMaterialCode(), rowType);
      BigDecimal amount =
          finalUnitPrice
              .multiply(row.getQtyPerTop())
              .setScale(8, RoundingMode.HALF_UP);
      String key = row.getMaterialCode() + "|" + rowType;
      PriceLine previous =
          lines.put(
              key,
              new PriceLine(
                  row.getMaterialCode(),
                  rowType,
                  identity.priceType(),
                  identity.priceSource(),
                  finalUnitPrice,
                  amount));
      if (previous != null) {
        throw new IllegalStateException(
            "QBA-13价格对象业务键重复: " + key);
      }
      total = total.add(amount);
    }
    return new PriceSnapshot(
        Map.copyOf(lines),
        total.setScale(8, RoundingMode.HALF_UP));
  }

  private static Map<String, PriceLine> withoutSensitiveChip(
      Map<String, PriceLine> source) {
    Map<String, PriceLine> result = new LinkedHashMap<>();
    source.forEach(
        (key, line) -> {
          if (!"201850547".equals(line.materialCode())
              && !"201850347".equals(line.materialCode())) {
            result.put(key, line);
          }
        });
    return result;
  }

  private static PriceIdentity priceIdentity(
      String settlementRowType) {
    return switch (settlementRowType) {
      case "SPECIAL_ROLLUP_PARENT" ->
          new PriceIdentity("自制件价格", "自制件生成");
      case "PACKAGE_PARENT" ->
          new PriceIdentity("包装组件价格", "包装组件价格");
      case "OUTSOURCED_PROCESS_FEE" ->
          new PriceIdentity("委外加工价格", "委外加工价");
      case "BYPRODUCT_EXTRA" ->
          new PriceIdentity("废料价格", "废料映射/副产品");
      default ->
          new PriceIdentity("结算固定价", "结算固定价");
    };
  }

  private static BigDecimal unitPrice(
      String materialCode, String settlementRowType) {
    if ("201850547".equals(materialCode)) {
      return new BigDecimal("10.50");
    }
    if ("201850347".equals(materialCode)) {
      return new BigDecimal("13.00");
    }
    return switch (settlementRowType) {
      case "SPECIAL_ROLLUP_PARENT" ->
          new BigDecimal("8.00");
      case "PACKAGE_PARENT" ->
          new BigDecimal("4.00");
      case "OUTSOURCED_PROCESS_FEE" ->
          new BigDecimal("12.00");
      case "BYPRODUCT_EXTRA" ->
          new BigDecimal("2.50");
      default ->
          BigDecimal.ONE.add(
              new BigDecimal(
                      Math.floorMod(
                          materialCode.hashCode(), 17))
                  .movePointLeft(1));
    };
  }

  private record PriceIdentity(
      String priceType, String priceSource) {}

  private record PriceLine(
      String materialCode,
      String settlementRowType,
      String priceType,
      String priceSource,
      BigDecimal finalUnitPrice,
      BigDecimal amount) {}

  private record PriceSnapshot(
      Map<String, PriceLine> lines,
      BigDecimal totalCost) {}
}
