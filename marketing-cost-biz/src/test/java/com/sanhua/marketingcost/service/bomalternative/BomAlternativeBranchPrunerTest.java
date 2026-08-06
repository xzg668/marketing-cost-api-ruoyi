package com.sanhua.marketingcost.service.bomalternative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.entity.BomRawHierarchy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QBA-06 BOM标准/替代分支裁剪")
class BomAlternativeBranchPrunerTest {

  private final BomAlternativePrunerTestSupport support =
      new BomAlternativePrunerTestSupport();

  @Test
  @DisplayName("默认标准件只保留标准分支及其全部后代")
  void keepsDefaultStandardAndItsWholeSubtree() {
    List<BomRawHierarchy> rows = support.baseTree();

    BomAlternativePruneResult result =
        support.pruner.prune(
            new BomAlternativePruneRequest(
                rows,
                List.of(support.mainGroup(rows)),
                Map.of(BomAlternativePrunerTestSupport.GROUP_MAIN, "STD")));

    assertThat(result.nodes())
        .extracting(BomRawHierarchy::getMaterialCode)
        .contains("STD", "STD-CHILD", "STD-GRAND")
        .doesNotContain("ALT", "ALT-CHILD", "ALT-GRAND");
    assertThat(result.removedNodeCount()).isEqualTo(3);
    assertThat(result.processedGroupCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("选择替代件时删除标准整棵子树并保留替代整棵子树")
  void keepsAlternativeAndItsWholeSubtree() {
    List<BomRawHierarchy> rows = support.baseTree();

    BomAlternativePruneResult result =
        support.pruner.prune(
            new BomAlternativePruneRequest(
                rows,
                List.of(support.mainGroup(rows)),
                Map.of(BomAlternativePrunerTestSupport.GROUP_MAIN, "ALT")));

    assertThat(result.nodes())
        .extracting(BomRawHierarchy::getMaterialCode)
        .contains("ALT", "ALT-CHILD", "ALT-GRAND")
        .doesNotContain("STD", "STD-CHILD", "STD-GRAND");
    assertThat(result.outputNodeCount()).isEqualTo(6);
  }

  @Test
  @DisplayName("位于两个平行位置的替代组分别按自己的选择裁剪")
  void prunesParallelGroupsIndependently() {
    List<BomRawHierarchy> rows = support.baseTree();
    rows.add(
        support.row(
            20,
            "PARENT-2",
            "TOP",
            1,
            "/TOP/PARENT-2/",
            null,
            null,
            "1",
            "1"));
    BomRawHierarchy standard2 =
        support.row(
            21,
            "STD-2",
            "PARENT-2",
            2,
            "/TOP/PARENT-2/STD-2/",
            BomAlternativePrunerTestSupport.GROUP_PARALLEL,
            "STANDARD",
            "1",
            "1");
    BomRawHierarchy alternative2 =
        support.row(
            22,
            "ALT-2",
            "PARENT-2",
            2,
            "/TOP/PARENT-2/ALT-2/",
            BomAlternativePrunerTestSupport.GROUP_PARALLEL,
            "ALTERNATIVE",
            "1",
            "1");
    rows.add(standard2);
    rows.add(alternative2);

    BomAlternativePruneResult result =
        support.pruner.prune(
            new BomAlternativePruneRequest(
                rows,
                List.of(
                    support.mainGroup(rows),
                    support.group(
                        BomAlternativePrunerTestSupport.GROUP_PARALLEL,
                        "PARENT-2",
                        standard2,
                        alternative2)),
                Map.of(
                    BomAlternativePrunerTestSupport.GROUP_MAIN,
                    "ALT",
                    BomAlternativePrunerTestSupport.GROUP_PARALLEL,
                    "STD-2")));

    assertThat(result.nodes())
        .extracting(BomRawHierarchy::getMaterialCode)
        .contains("ALT", "STD-2")
        .doesNotContain("STD", "ALT-2");
    assertThat(result.processedGroupCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("相同料号出现在其他路径时只删除未选中的发生位置")
  void usesOccurrencePathInsteadOfGlobalMaterialCode() {
    List<BomRawHierarchy> rows = support.baseTree();
    BomRawHierarchy sameMaterialElsewhere =
        support.row(
            30,
            "ALT",
            "ORDINARY",
            2,
            "/TOP/ORDINARY/ALT/",
            null,
            null,
            "1",
            "1");
    rows.add(sameMaterialElsewhere);

    BomAlternativePruneResult result =
        support.pruner.prune(
            new BomAlternativePruneRequest(
                rows,
                List.of(support.mainGroup(rows)),
                Map.of(BomAlternativePrunerTestSupport.GROUP_MAIN, "STD")));

    assertThat(result.nodes())
        .filteredOn(row -> "ALT".equals(row.getMaterialCode()))
        .extracting(BomRawHierarchy::getPath)
        .containsExactly("/TOP/ORDINARY/ALT/");
  }

  @Test
  @DisplayName("输入顺序变化后输出仍使用相同的稳定业务顺序")
  void outputOrderIsStableAcrossInputOrders() {
    List<BomRawHierarchy> first = support.baseTree();
    List<BomRawHierarchy> shuffled = new ArrayList<>(first);
    Collections.reverse(shuffled);
    BomAlternativeGroup group = support.mainGroup(first);
    BomAlternativePruneRequest firstRequest =
        new BomAlternativePruneRequest(
            first,
            List.of(group),
            Map.of(BomAlternativePrunerTestSupport.GROUP_MAIN, "ALT"));
    BomAlternativePruneRequest shuffledRequest =
        new BomAlternativePruneRequest(
            shuffled,
            List.of(group),
            Map.of(BomAlternativePrunerTestSupport.GROUP_MAIN, "ALT"));

    List<String> firstPaths =
        support.pruner.prune(firstRequest).nodes().stream()
            .map(BomRawHierarchy::getPath)
            .toList();
    List<String> shuffledPaths =
        support.pruner.prune(shuffledRequest).nodes().stream()
            .map(BomRawHierarchy::getPath)
            .toList();

    assertThat(shuffledPaths).containsExactlyElementsOf(firstPaths);
  }

  @Test
  @DisplayName("选中分支根节点缺失时阻断并给出稳定错误码")
  void blocksWhenSelectedBranchRootIsMissing() {
    List<BomRawHierarchy> complete = support.baseTree();
    BomAlternativeGroup group = support.mainGroup(complete);
    List<BomRawHierarchy> missingSelectedRoot =
        complete.stream()
            .filter(row -> !"/TOP/PARENT/ALT/".equals(row.getPath()))
            .toList();

    assertThatThrownBy(
            () ->
                support.pruner.prune(
                    new BomAlternativePruneRequest(
                        missingSelectedRoot,
                        List.of(group),
                        Map.of(
                            BomAlternativePrunerTestSupport.GROUP_MAIN,
                            "ALT"))))
        .isInstanceOf(QuoteBomAlternativeSelectionException.class)
        .extracting("code")
        .isEqualTo("ALT_BRANCH_STRUCTURE_MISSING");
  }

  @Test
  @DisplayName("选中分支中间节点缺失但孙节点残留时同样阻断")
  void blocksWhenSelectedBranchPathChainIsBroken() {
    List<BomRawHierarchy> complete = support.baseTree();
    BomAlternativeGroup group = support.mainGroup(complete);
    List<BomRawHierarchy> brokenSelectedBranch =
        complete.stream()
            .filter(
                row ->
                    !"/TOP/PARENT/ALT/ALT-CHILD/".equals(
                        row.getPath()))
            .toList();

    assertThatThrownBy(
            () ->
                support.pruner.prune(
                    new BomAlternativePruneRequest(
                        brokenSelectedBranch,
                        List.of(group),
                        Map.of(
                            BomAlternativePrunerTestSupport.GROUP_MAIN,
                            "ALT"))))
        .isInstanceOf(QuoteBomAlternativeSelectionException.class)
        .hasMessageContaining("父子路径链断裂")
        .extracting("code")
        .isEqualTo("ALT_BRANCH_STRUCTURE_MISSING");
  }

  @Test
  @DisplayName("未选中分支根节点缺失不阻断且残留后代仍被删除")
  void ignoresMissingUnselectedRootAndRemovesItsDescendants() {
    List<BomRawHierarchy> complete = support.baseTree();
    BomAlternativeGroup group = support.mainGroup(complete);
    List<BomRawHierarchy> missingUnselectedRoot =
        complete.stream()
            .filter(row -> !"/TOP/PARENT/ALT/".equals(row.getPath()))
            .toList();

    BomAlternativePruneResult result =
        support.pruner.prune(
            new BomAlternativePruneRequest(
                missingUnselectedRoot,
                List.of(group),
                Map.of(BomAlternativePrunerTestSupport.GROUP_MAIN, "STD")));

    assertThat(result.nodes())
        .extracting(BomRawHierarchy::getMaterialCode)
        .doesNotContain("ALT-CHILD", "ALT-GRAND");
    assertThat(result.warnings())
        .anyMatch(warning -> warning.startsWith("ALT_UNSELECTED_BRANCH_MISSING:"));
  }

  @Test
  @DisplayName("可达替代组缺少选择时阻断而不是偷偷默认第一条")
  void blocksWhenReachableGroupHasNoSelection() {
    List<BomRawHierarchy> rows = support.baseTree();

    assertThatThrownBy(
            () ->
                support.pruner.prune(
                    new BomAlternativePruneRequest(
                        rows, List.of(support.mainGroup(rows)), Map.of())))
        .isInstanceOf(QuoteBomAlternativeSelectionException.class)
        .extracting("code")
        .isEqualTo("ALT_SELECTION_MISSING");
  }

  @Test
  @DisplayName("选中料号不属于当前组时拒绝裁剪")
  void rejectsSelectionOutsideCurrentGroup() {
    List<BomRawHierarchy> rows = support.baseTree();
    Map<String, String> selections = new LinkedHashMap<>();
    selections.put(BomAlternativePrunerTestSupport.GROUP_MAIN, "OTHER");

    assertThatThrownBy(
            () ->
                support.pruner.prune(
                    new BomAlternativePruneRequest(
                        rows, List.of(support.mainGroup(rows)), selections)))
        .isInstanceOf(QuoteBomAlternativeSelectionException.class)
        .extracting("code")
        .isEqualTo("ALT_CANDIDATE_INVALID");
  }

  @Test
  @DisplayName("没有替代组的普通BOM节点全部原样保留")
  void keepsOrdinaryBomWhenNoAlternativeGroupExists() {
    List<BomRawHierarchy> rows =
        support.baseTree().stream()
            .filter(
                row ->
                    "TOP".equals(row.getMaterialCode())
                        || "ORDINARY".equals(row.getMaterialCode()))
            .toList();

    BomAlternativePruneResult result =
        support.pruner.prune(
            new BomAlternativePruneRequest(rows, List.of(), Map.of()));

    assertThat(result.outputNodeCount()).isEqualTo(rows.size());
    assertThat(result.removedNodeCount()).isZero();
    assertThat(result.nodes()).containsExactlyInAnyOrderElementsOf(rows);
  }
}
