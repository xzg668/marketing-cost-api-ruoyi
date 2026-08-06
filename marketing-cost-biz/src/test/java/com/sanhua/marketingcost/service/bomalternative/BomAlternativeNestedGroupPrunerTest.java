package com.sanhua.marketingcost.service.bomalternative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.entity.BomRawHierarchy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QBA-06 嵌套替代组递归裁剪")
class BomAlternativeNestedGroupPrunerTest {

  private final BomAlternativePrunerTestSupport support =
      new BomAlternativePrunerTestSupport();

  @Test
  @DisplayName("选中分支内部存在下级替代组时继续递归保留唯一分支")
  void recursivelyPrunesNestedGroupInsideSelectedBranch() {
    Fixture fixture = selectedNestedFixture();

    BomAlternativePruneResult result =
        support.pruner.prune(
            new BomAlternativePruneRequest(
                fixture.rows(),
                List.of(fixture.outerGroup(), fixture.nestedGroup()),
                Map.of(
                    BomAlternativePrunerTestSupport.GROUP_MAIN,
                    "STD",
                    BomAlternativePrunerTestSupport.GROUP_NESTED_SELECTED,
                    "NESTED-ALT")));

    assertThat(result.nodes())
        .extracting(BomRawHierarchy::getMaterialCode)
        .contains("STD", "NESTED-ALT", "NESTED-ALT-LEAF")
        .doesNotContain(
            "ALT",
            "ALT-CHILD",
            "ALT-GRAND",
            "NESTED-STD",
            "NESTED-STD-LEAF");
    assertThat(result.processedGroupKeys())
        .containsExactly(
            BomAlternativePrunerTestSupport.GROUP_MAIN,
            BomAlternativePrunerTestSupport.GROUP_NESTED_SELECTED);
  }

  @Test
  @DisplayName("未选中父分支内的下级替代组无需选择且不会创建默认处理")
  void skipsNestedGroupInsideUnselectedBranchWithoutSelection() {
    Fixture fixture = unselectedNestedFixture();

    BomAlternativePruneResult result =
        support.pruner.prune(
            new BomAlternativePruneRequest(
                fixture.rows(),
                List.of(fixture.nestedGroup(), fixture.outerGroup()),
                Map.of(BomAlternativePrunerTestSupport.GROUP_MAIN, "STD")));

    assertThat(result.skippedGroupKeys())
        .containsExactly(
            BomAlternativePrunerTestSupport.GROUP_NESTED_UNSELECTED);
    assertThat(result.processedGroupKeys())
        .containsExactly(BomAlternativePrunerTestSupport.GROUP_MAIN);
    assertThat(result.nodes())
        .extracting(BomRawHierarchy::getMaterialCode)
        .doesNotContain(
            "ALT",
            "ALT-CHILD",
            "ALT-GRAND",
            "UNSELECTED-NESTED-STD",
            "UNSELECTED-NESTED-ALT");
  }

  @Test
  @DisplayName("选中父分支内的下级替代组缺少选择时必须阻断")
  void nestedReachableGroupStillRequiresSelection() {
    Fixture fixture = selectedNestedFixture();

    assertThatThrownBy(
            () ->
                support.pruner.prune(
                    new BomAlternativePruneRequest(
                        fixture.rows(),
                        List.of(fixture.nestedGroup(), fixture.outerGroup()),
                        Map.of(BomAlternativePrunerTestSupport.GROUP_MAIN, "STD"))))
        .isInstanceOf(QuoteBomAlternativeSelectionException.class)
        .extracting("code")
        .isEqualTo("ALT_SELECTION_MISSING");
  }

  private Fixture selectedNestedFixture() {
    List<BomRawHierarchy> rows = support.baseTree();
    BomRawHierarchy nestedStandard =
        support.row(
            40,
            "NESTED-STD",
            "STD",
            3,
            "/TOP/PARENT/STD/NESTED-STD/",
            BomAlternativePrunerTestSupport.GROUP_NESTED_SELECTED,
            "STANDARD",
            "2",
            "4");
    BomRawHierarchy nestedStandardLeaf =
        support.row(
            41,
            "NESTED-STD-LEAF",
            "NESTED-STD",
            4,
            "/TOP/PARENT/STD/NESTED-STD/NESTED-STD-LEAF/",
            null,
            null,
            "3",
            "12");
    BomRawHierarchy nestedAlternative =
        support.row(
            42,
            "NESTED-ALT",
            "STD",
            3,
            "/TOP/PARENT/STD/NESTED-ALT/",
            BomAlternativePrunerTestSupport.GROUP_NESTED_SELECTED,
            "ALTERNATIVE",
            "5",
            "10");
    BomRawHierarchy nestedAlternativeLeaf =
        support.row(
            43,
            "NESTED-ALT-LEAF",
            "NESTED-ALT",
            4,
            "/TOP/PARENT/STD/NESTED-ALT/NESTED-ALT-LEAF/",
            null,
            null,
            "7",
            "70");
    rows.addAll(
        List.of(
            nestedStandard,
            nestedStandardLeaf,
            nestedAlternative,
            nestedAlternativeLeaf));
    return new Fixture(
        rows,
        support.mainGroup(rows),
        support.group(
            BomAlternativePrunerTestSupport.GROUP_NESTED_SELECTED,
            "STD",
            nestedStandard,
            nestedAlternative));
  }

  private Fixture unselectedNestedFixture() {
    List<BomRawHierarchy> rows = support.baseTree();
    BomRawHierarchy nestedStandard =
        support.row(
            50,
            "UNSELECTED-NESTED-STD",
            "ALT",
            3,
            "/TOP/PARENT/ALT/UNSELECTED-NESTED-STD/",
            BomAlternativePrunerTestSupport.GROUP_NESTED_UNSELECTED,
            "STANDARD",
            "1",
            "4");
    BomRawHierarchy nestedAlternative =
        support.row(
            51,
            "UNSELECTED-NESTED-ALT",
            "ALT",
            3,
            "/TOP/PARENT/ALT/UNSELECTED-NESTED-ALT/",
            BomAlternativePrunerTestSupport.GROUP_NESTED_UNSELECTED,
            "ALTERNATIVE",
            "1",
            "4");
    rows.add(nestedStandard);
    rows.add(nestedAlternative);
    return new Fixture(
        rows,
        support.mainGroup(rows),
        support.group(
            BomAlternativePrunerTestSupport.GROUP_NESTED_UNSELECTED,
            "ALT",
            nestedStandard,
            nestedAlternative));
  }

  private record Fixture(
      List<BomRawHierarchy> rows,
      BomAlternativeGroup outerGroup,
      BomAlternativeGroup nestedGroup) {
  }
}
