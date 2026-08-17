package com.sanhua.marketingcost.dto.collaboration;

import java.math.BigDecimal;
import java.util.List;

/** 独立补录草稿；tree 与 flatNodes 来自同一份父子数据，避免只做缩进展示。 */
public record TechnicalBomDraftResponse(
    Long supplementVersionId,
    Integer taskVersion,
    String sourceMode,
    String referenceProductCode,
    boolean exportReady,
    List<Issue> issues,
    List<Node> tree,
    List<Node> flatNodes) {

  public TechnicalBomDraftResponse {
    issues = issues == null ? List.of() : List.copyOf(issues);
    tree = tree == null ? List.of() : List.copyOf(tree);
    flatNodes = flatNodes == null ? List.of() : List.copyOf(flatNodes);
  }

  public record Issue(String nodeId, String code, String message) {}

  public record Node(
      String nodeId,
      String parentNodeId,
      int level,
      String materialCode,
      boolean temporaryMaterial,
      String materialName,
      String materialSpec,
      String materialModel,
      String drawingNo,
      String materialNature,
      BigDecimal quantity,
      BigDecimal quantityToTop,
      String unit,
      Integer sortSeq,
      boolean changed,
      List<Node> children) {

    public Node {
      children = children == null ? List.of() : List.copyOf(children);
    }
  }
}
