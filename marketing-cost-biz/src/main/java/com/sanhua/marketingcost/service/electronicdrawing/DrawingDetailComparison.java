package com.sanhua.marketingcost.service.electronicdrawing;

import com.sanhua.marketingcost.entity.ElectronicDrawingSourceNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 比较两次图库原始明细；不把任务号、接收时间或报价系统补录行当成图库变化。 */
public final class DrawingDetailComparison {
  private DrawingDetailComparison() {}

  public record Match(ElectronicDrawingSourceNode previous, ElectronicDrawingSourceNode current,
      boolean quantityChanged, boolean contentChanged) {
    public boolean unchanged() { return !quantityChanged && !contentChanged; }
  }

  public record Result(List<Match> matches, List<ElectronicDrawingSourceNode> added,
      List<ElectronicDrawingSourceNode> removed, boolean sameTree) {
    public Result {
      matches = List.copyOf(matches);
      added = List.copyOf(added);
      removed = List.copyOf(removed);
    }
    public boolean unchanged() {
      return sameTree;
    }
  }

  public static Result compare(List<ElectronicDrawingSourceNode> previous,
      List<ElectronicDrawingSourceNode> current) {
    validate(previous);
    validate(current);
    var before = children(previous);
    var after = children(current);
    var matches = new ArrayList<Match>();
    var added = new ArrayList<ElectronicDrawingSourceNode>();
    var removed = new ArrayList<ElectronicDrawingSourceNode>();
    compareSiblings(before.getOrDefault(null, List.of()), after.getOrDefault(null, List.of()),
        before, after, matches, added, removed);
    return new Result(matches, added, removed,
        added.isEmpty() && removed.isEmpty() && matches.stream().allMatch(Match::unchanged));
  }

  private static void compareSiblings(List<ElectronicDrawingSourceNode> oldSiblings,
      List<ElectronicDrawingSourceNode> newSiblings,
      Map<String, List<ElectronicDrawingSourceNode>> before,
      Map<String, List<ElectronicDrawingSourceNode>> after, List<Match> matches,
      List<ElectronicDrawingSourceNode> added, List<ElectronicDrawingSourceNode> removed) {
    var oldRows = new ArrayList<>(oldSiblings);
    var newRows = new ArrayList<>(newSiblings);
    // 先对齐整棵相同子树，再比较同一父节点下的差异，不能跨重复父件套用补录。
    for (var row : List.copyOf(newRows)) {
      var identical = oldRows.stream().filter(old -> tree(old, before).equals(tree(row, after)))
          .findFirst().orElse(null);
      if (identical != null) pair(identical, row, oldRows, newRows, before, after, matches, added, removed);
    }
    for (var row : List.copyOf(newRows)) {
      var candidates = oldRows.stream().filter(old -> sameContent(old, row)).toList();
      long occurrences = newRows.stream().filter(next -> sameContent(next, row)).count();
      if (candidates.size() == 1 && occurrences == 1)
        pair(candidates.getFirst(), row, oldRows, newRows, before, after, matches, added, removed);
    }
    for (var row : List.copyOf(newRows)) {
      var candidates = oldRows.stream().filter(old -> Objects.equals(old.getDrawingCode(), row.getDrawingCode())).toList();
      long occurrences = newRows.stream().filter(next -> Objects.equals(next.getDrawingCode(), row.getDrawingCode())).count();
      if (candidates.size() == 1 && occurrences == 1)
        pair(candidates.getFirst(), row, oldRows, newRows, before, after, matches, added, removed);
    }
    for (var row : oldRows) collect(row, before, removed);
    for (var row : newRows) collect(row, after, added);
  }

  private static void pair(ElectronicDrawingSourceNode old, ElectronicDrawingSourceNode row,
      List<ElectronicDrawingSourceNode> oldRows, List<ElectronicDrawingSourceNode> newRows,
      Map<String, List<ElectronicDrawingSourceNode>> before,
      Map<String, List<ElectronicDrawingSourceNode>> after, List<Match> matches,
      List<ElectronicDrawingSourceNode> added, List<ElectronicDrawingSourceNode> removed) {
    matches.add(new Match(old, row, !same(old.getQty(), row.getQty()), !sameContent(old, row)));
    oldRows.remove(old); newRows.remove(row);
    compareSiblings(before.getOrDefault(old.getSourceSequence(), List.of()), after.getOrDefault(row.getSourceSequence(), List.of()),
        before, after, matches, added, removed);
  }

  private static void collect(ElectronicDrawingSourceNode row,
      Map<String, List<ElectronicDrawingSourceNode>> children, List<ElectronicDrawingSourceNode> output) {
    output.add(row);
    for (var child : children.getOrDefault(row.getSourceSequence(), List.of())) collect(child, children, output);
  }

  private record Tree(List<Object> values, Map<Tree, Long> children) {}

  private static Map<String, List<ElectronicDrawingSourceNode>> children(List<ElectronicDrawingSourceNode> rows) {
    var result = new HashMap<String, List<ElectronicDrawingSourceNode>>();
    for (var row : rows) result.computeIfAbsent(row.getParentSourceSequence(), ignored -> new ArrayList<>()).add(row);
    return result;
  }

  private static Tree tree(ElectronicDrawingSourceNode row, Map<String, List<ElectronicDrawingSourceNode>> children) {
    var values = java.util.Arrays.<Object>asList(row.getDrawingCode(), row.getSourceName(),
        number(row.getQty()), row.getQuantityUnit(), row.getMaterial(), row.getImportanceClass(),
        row.getHsfRiskClass(), number(row.getReferenceWeight()), row.getReferenceWeightUnit(), row.getSourceRemark());
    var subtrees = new HashMap<Tree, Long>();
    for (var child : children.getOrDefault(row.getSourceSequence(), List.of())) subtrees.merge(tree(child, children), 1L, Long::sum);
    return new Tree(values, Map.copyOf(subtrees));
  }

  private static BigDecimal number(BigDecimal value) {
    return value == null ? null : value.stripTrailingZeros();
  }

  private static void validate(List<ElectronicDrawingSourceNode> rows) {
    var bySequence = new HashMap<String, ElectronicDrawingSourceNode>();
    for (var row : rows) {
      if (row.getSourceSequence() == null || bySequence.putIfAbsent(row.getSourceSequence(), row) != null)
        throw new IllegalArgumentException("图库明细行标识缺失或重复，不能比较历史版本");
    }
    for (var row : rows) {
      var seen = new java.util.HashSet<String>();
      for (var node = row; node != null;) {
        if (!seen.add(node.getSourceSequence())) throw new IllegalArgumentException("图库明细存在循环层级");
        var parent = node.getParentSourceSequence();
        node = parent == null ? null : bySequence.get(parent);
        if (parent != null && node == null) throw new IllegalArgumentException("图库明细缺少父项，不能比较历史版本");
      }
    }
  }

  private static boolean sameContent(ElectronicDrawingSourceNode a, ElectronicDrawingSourceNode b) {
    return Objects.equals(a.getDrawingCode(), b.getDrawingCode())
        && Objects.equals(a.getSourceName(), b.getSourceName())
        && Objects.equals(a.getMaterial(), b.getMaterial())
        && Objects.equals(a.getImportanceClass(), b.getImportanceClass())
        && Objects.equals(a.getHsfRiskClass(), b.getHsfRiskClass())
        && same(a.getReferenceWeight(), b.getReferenceWeight())
        && Objects.equals(a.getReferenceWeightUnit(), b.getReferenceWeightUnit())
        && Objects.equals(a.getQuantityUnit(), b.getQuantityUnit())
        && Objects.equals(a.getSourceRemark(), b.getSourceRemark());
  }

  private static boolean same(BigDecimal a, BigDecimal b) {
    return a == null ? b == null : b != null && a.compareTo(b) == 0;
  }
}
