package com.sanhua.marketingcost.dto.collaboration;

import java.math.BigDecimal;
import java.util.List;

/** 完整树保存请求；父子关系使用页面节点 ID 表达，服务端重新生成层级、路径和累计用量。 */
public record TechnicalBomDraftRequest(
    Integer expectedTaskVersion,
    List<Node> nodes) {

  public TechnicalBomDraftRequest {
    nodes = nodes == null ? List.of() : List.copyOf(nodes);
  }

  public record Node(
      String nodeId,
      String parentNodeId,
      String materialCode,
      String materialName,
      String materialSpec,
      String materialModel,
      String drawingNo,
      String materialNature,
      BigDecimal quantity,
      String unit,
      Integer sortSeq,
      boolean changed) {}
}
