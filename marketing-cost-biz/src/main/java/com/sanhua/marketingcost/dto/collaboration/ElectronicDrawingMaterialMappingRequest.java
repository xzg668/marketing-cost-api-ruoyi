package com.sanhua.marketingcost.dto.collaboration;

import java.util.List;

/** 技术人员对未匹配或多匹配电子图库节点作出的正式 U9 料号选择。 */
public record ElectronicDrawingMaterialMappingRequest(
    Integer expectedVersion,
    List<Selection> selections) {
  public ElectronicDrawingMaterialMappingRequest {
    selections = selections == null ? List.of() : List.copyOf(selections);
  }

  public record Selection(String nodeId, String materialCode) {}
}
