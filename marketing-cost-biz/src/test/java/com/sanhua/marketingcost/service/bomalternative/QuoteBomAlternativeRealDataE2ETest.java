package com.sanhua.marketingcost.service.bomalternative;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.QuoteBomAlternativeSelection;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QBA-12 1145900000302标准/替代/恢复真实结构端到端")
class QuoteBomAlternativeRealDataE2ETest {

  private QuoteBomAlternativeRealDataTestSupport support;

  @BeforeEach
  void setUp() {
    support = new QuoteBomAlternativeRealDataTestSupport();
  }

  @Test
  @DisplayName("默认标准只保留201850659整棵子树并生成版本1")
  void defaultsToStandardWithoutAlternativeBranch() {
    var round = support.defaultStandard();

    assertThat(support.sourceRows()).hasSize(79);
    assertThat(round.pruned().inputNodeCount()).isEqualTo(79);
    assertThat(round.pruned().outputNodeCount()).isEqualTo(56);
    assertThat(round.pruned().removedNodeCount()).isEqualTo(23);
    assertThat(round.selection().selectedMaterialCode())
        .isEqualTo(QuoteBomAlternativeRealDataTestSupport.STANDARD);
    assertThat(round.selection().selectionSource())
        .isEqualTo(QuoteBomAlternativeSelection.SOURCE_AUTO_STANDARD);
    assertThat(round.selection().selectionVersion()).isEqualTo(1);
    assertThat(round.replaceCount()).isZero();
    assertThat(round.costingRows()).hasSize(34);
    assertSingleBranch(round.costingRows(),
        QuoteBomAlternativeRealDataTestSupport.STANDARD,
        QuoteBomAlternativeRealDataTestSupport.ALTERNATIVE);
    assertThat(round.costingRows())
        .extracting(BomCostingRow::getMaterialCode)
        .contains("201850547")
        .doesNotContain("201850347");
    assertNotDoubleCounted(round.costingRows());
  }

  @Test
  @DisplayName("选择替代后标准子树退出且整页重建和下游失效")
  void switchesToAlternativeWithoutDoubleCounting() {
    var standard = support.defaultStandard();
    var alternative = support.selectAlternative();

    assertThat(alternative.selection().selectedMaterialCode())
        .isEqualTo(QuoteBomAlternativeRealDataTestSupport.ALTERNATIVE);
    assertThat(alternative.selection().selectionSource())
        .isEqualTo(QuoteBomAlternativeSelection.SOURCE_MANUAL_ALTERNATIVE);
    assertThat(alternative.selection().selectionVersion()).isEqualTo(2);
    assertThat(alternative.replaceCount()).isEqualTo(1);
    assertThat(alternative.rebuild()).isNotNull();
    assertThat(alternative.rebuild().rebuilt()).isTrue();
    assertThat(alternative.rebuild().priceTypeInvalidatedCount()).isPositive();
    assertThat(alternative.rebuild().pricePrepareInvalidatedCount()).isPositive();
    assertThat(alternative.rebuild().costRunInvalidatedCount()).isPositive();
    assertThat(alternative.costingRows()).hasSameSizeAs(standard.costingRows());
    assertSingleBranch(alternative.costingRows(),
        QuoteBomAlternativeRealDataTestSupport.ALTERNATIVE,
        QuoteBomAlternativeRealDataTestSupport.STANDARD);
    assertThat(alternative.costingRows())
        .extracting(BomCostingRow::getMaterialCode)
        .contains("201850347")
        .doesNotContain("201850547");
    assertNotDoubleCounted(alternative.costingRows());
  }

  @Test
  @DisplayName("恢复标准形成版本3且报价明细回到第一轮")
  void restoresStandardAndKeepsThreeVersionHistory() {
    var first = support.defaultStandard();
    support.selectAlternative();
    var restored = support.restoreStandard();

    assertThat(restored.selection().selectedMaterialCode())
        .isEqualTo(QuoteBomAlternativeRealDataTestSupport.STANDARD);
    assertThat(restored.selection().selectionSource())
        .isEqualTo(QuoteBomAlternativeSelection.SOURCE_MANUAL_STANDARD);
    assertThat(restored.selection().selectionVersion()).isEqualTo(3);
    assertThat(restored.replaceCount()).isZero();
    assertSingleBranch(restored.costingRows(),
        QuoteBomAlternativeRealDataTestSupport.STANDARD,
        QuoteBomAlternativeRealDataTestSupport.ALTERNATIVE);
    assertThat(restored.costingRowKeys())
        .containsExactlyElementsOf(first.costingRowKeys());
    assertThat(support.history())
        .extracting(QuoteBomAlternativeSelectionResult::selectionVersion)
        .containsExactly(1, 2, 3);
    assertThat(support.history())
        .extracting(QuoteBomAlternativeSelectionResult::selectionSource)
        .containsExactly(
            QuoteBomAlternativeSelection.SOURCE_AUTO_STANDARD,
            QuoteBomAlternativeSelection.SOURCE_MANUAL_ALTERNATIVE,
            QuoteBomAlternativeSelection.SOURCE_MANUAL_STANDARD);
  }

  private static void assertSingleBranch(
      List<BomCostingRow> rows,
      String expectedBranch,
      String excludedBranch) {
    assertThat(rows)
        .anyMatch(row -> row.getPath().contains("/" + expectedBranch + "@10@010/"))
        .noneMatch(row -> row.getPath().contains("/" + excludedBranch + "@10@010/"));
  }

  private static void assertNotDoubleCounted(
      List<BomCostingRow> rows) {
    assertThat(rows)
        .filteredOn(
            row ->
                "201850115".equals(
                    row.getMaterialCode()))
        .singleElement()
        .extracting(BomCostingRow::getQtyPerTop)
        .isEqualTo(BigDecimal.ONE);
    assertThat(rows)
        .filteredOn(
            row ->
                "BYPRODUCT_EXTRA".equals(
                    row.getSettlementRowType()))
        .hasSize(2);
  }
}
