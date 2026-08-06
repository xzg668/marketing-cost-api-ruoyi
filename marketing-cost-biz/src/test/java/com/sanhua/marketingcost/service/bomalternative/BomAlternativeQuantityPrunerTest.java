package com.sanhua.marketingcost.service.bomalternative;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.entity.BomRawHierarchy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QBA-06 替代分支用量与纯函数约束")
class BomAlternativeQuantityPrunerTest {

  private final BomAlternativePrunerTestSupport support =
      new BomAlternativePrunerTestSupport();

  @Test
  @DisplayName("标准和替代用量不同时分别保留来源分支自己的用量")
  void preservesSelectedBranchOwnQuantities() {
    List<BomRawHierarchy> rows = support.baseTree();
    BomAlternativeGroup group = support.mainGroup(rows);

    BomAlternativePruneResult standard =
        support.pruner.prune(
            new BomAlternativePruneRequest(
                rows,
                List.of(group),
                Map.of(BomAlternativePrunerTestSupport.GROUP_MAIN, "STD")));
    BomAlternativePruneResult alternative =
        support.pruner.prune(
            new BomAlternativePruneRequest(
                rows,
                List.of(group),
                Map.of(BomAlternativePrunerTestSupport.GROUP_MAIN, "ALT")));

    assertThat(find(standard.nodes(), "STD").getQtyPerParent())
        .isEqualByComparingTo("2");
    assertThat(find(standard.nodes(), "STD-GRAND").getQtyPerTop())
        .isEqualByComparingTo("30");
    assertThat(find(alternative.nodes(), "ALT").getQtyPerParent())
        .isEqualByComparingTo("4");
    assertThat(find(alternative.nodes(), "ALT-GRAND").getQtyPerTop())
        .isEqualByComparingTo("308");
  }

  @Test
  @DisplayName("裁剪不修改输入集合顺序或任何原节点字段")
  void doesNotMutateInputCollectionOrRows() {
    List<BomRawHierarchy> rows = support.baseTree();
    List<Long> originalIds = rows.stream().map(BomRawHierarchy::getId).toList();
    List<String> originalPaths =
        rows.stream().map(BomRawHierarchy::getPath).toList();
    List<BigDecimal> originalQuantities =
        rows.stream().map(BomRawHierarchy::getQtyPerTop).toList();

    BomAlternativePruneResult result =
        support.pruner.prune(
            new BomAlternativePruneRequest(
                rows,
                List.of(support.mainGroup(rows)),
                Map.of(BomAlternativePrunerTestSupport.GROUP_MAIN, "ALT")));

    assertThat(rows).hasSize(9);
    assertThat(rows.stream().map(BomRawHierarchy::getId).toList())
        .containsExactlyElementsOf(originalIds);
    assertThat(rows.stream().map(BomRawHierarchy::getPath).toList())
        .containsExactlyElementsOf(originalPaths);
    assertThat(rows.stream().map(BomRawHierarchy::getQtyPerTop).toList())
        .containsExactlyElementsOf(originalQuantities);
    assertThat(find(result.nodes(), "ALT"))
        .isSameAs(find(rows, "ALT"));
  }

  @Test
  @DisplayName("裁剪不会生成重复路径或复制结算来源节点")
  void doesNotCreateDuplicatePathsOrRows() {
    List<BomRawHierarchy> rows = support.baseTree();

    BomAlternativePruneResult result =
        support.pruner.prune(
            new BomAlternativePruneRequest(
                rows,
                List.of(support.mainGroup(rows)),
                Map.of(BomAlternativePrunerTestSupport.GROUP_MAIN, "STD")));

    assertThat(result.nodes())
        .extracting(BomRawHierarchy::getPath)
        .doesNotHaveDuplicates();
    assertThat(result.nodes())
        .extracting(BomRawHierarchy::getId)
        .doesNotHaveDuplicates();
    assertThat(result.inputNodeCount())
        .isEqualTo(result.outputNodeCount() + result.removedNodeCount());
  }

  @Test
  @DisplayName("请求建立后外部修改原集合不会改变本次裁剪输入快照")
  void requestDefensivelyCopiesCollections() {
    List<BomRawHierarchy> rows = support.baseTree();
    List<BomAlternativeGroup> groups =
        new ArrayList<>(List.of(support.mainGroup(rows)));
    Map<String, String> selections =
        new java.util.LinkedHashMap<>(
            Map.of(BomAlternativePrunerTestSupport.GROUP_MAIN, "STD"));
    BomAlternativePruneRequest request =
        new BomAlternativePruneRequest(rows, groups, selections);
    rows.clear();
    groups.clear();
    selections.clear();

    BomAlternativePruneResult result = support.pruner.prune(request);

    assertThat(result.processedGroupCount()).isEqualTo(1);
    assertThat(result.nodes())
        .extracting(BomRawHierarchy::getMaterialCode)
        .contains("STD")
        .doesNotContain("ALT");
  }

  private BomRawHierarchy find(
      List<BomRawHierarchy> rows, String materialCode) {
    return rows.stream()
        .filter(row -> materialCode.equals(row.getMaterialCode()))
        .findFirst()
        .orElseThrow();
  }
}
