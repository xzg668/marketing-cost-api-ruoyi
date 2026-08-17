package com.sanhua.marketingcost.dto.collaboration;

import java.math.BigDecimal;
import java.util.List;

/** 裸品包装工作区：U9本体只读，技术只编辑独立包装草稿。 */
public record TechnicalPackageWorkspaceResponse(
    Long taskId,
    Integer taskVersion,
    String taskStatus,
    BodySummary u9Body,
    Draft draft,
    CombinedBom combinedBom,
    int openPriceGapCount,
    String guidance) {

  public record BodySummary(
      String productCode,
      boolean ready,
      int lineCount,
      String source,
      String message) {}

  public record Draft(
      Long packageReferenceId,
      String sourceMode,
      String sourceLabel,
      String referenceStatus,
      int lineCount,
      boolean edited,
      List<Line> lines,
      List<TreeNode> tree) {
    public Draft {
      lines = lines == null ? List.of() : List.copyOf(lines);
      tree = tree == null ? List.of() : List.copyOf(tree);
    }
  }

  public record Line(
      Long draftLineId,
      Integer lineNo,
      String packageParentCode,
      String packageParentName,
      String packageParentSpec,
      String packageParentModel,
      String packageParentUnit,
      BigDecimal packageQtyPerTop,
      String packageMaterialCode,
      String packageMaterialName,
      String packageMaterialSpec,
      String packageMaterialModel,
      String packageMaterialUnit,
      BigDecimal childQtyPerParent,
      BigDecimal qtyPerTop,
      String path,
      boolean changed) {}

  /** 供后续核算消费的只读候选结构：U9 本体原样保留，包装作为独立分支挂到裸品。 */
  public record CombinedBom(
      boolean ready,
      int bodyLineCount,
      int packageLineCount,
      int totalLineCount,
      List<CandidateLine> lines) {
    public CombinedBom {
      lines = lines == null ? List.of() : List.copyOf(lines);
    }
  }

  public record CandidateLine(
      String source,
      Integer level,
      String parentCode,
      String materialCode,
      String materialName,
      BigDecimal quantityPerParent,
      BigDecimal quantityPerTop,
      String unit,
      String path) {}

  public record TreeNode(
      String nodeKey,
      String materialCode,
      String materialName,
      BigDecimal quantity,
      String unit,
      boolean virtualPackage,
      List<TreeNode> children) {
    public TreeNode {
      children = children == null ? List.of() : List.copyOf(children);
    }
  }
}
