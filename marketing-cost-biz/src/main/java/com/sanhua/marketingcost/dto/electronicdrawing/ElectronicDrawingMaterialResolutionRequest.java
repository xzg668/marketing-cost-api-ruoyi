package com.sanhua.marketingcost.dto.electronicdrawing;

import java.util.List;

/** 财务报价员一次保存一条或多条电子图库物料的 U9 料号选择。 */
public record ElectronicDrawingMaterialResolutionRequest(
    Integer expectedTaskVersion,
    Long expectedSourceVersionId,
    List<Selection> selections) {

  public ElectronicDrawingMaterialResolutionRequest {
    selections = selections == null ? List.of() : List.copyOf(selections);
  }

  public record Selection(Long sourceNodeId, String materialCode) {}
}
