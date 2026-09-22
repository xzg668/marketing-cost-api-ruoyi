package com.sanhua.marketingcost.dto.electronicdrawing;

import java.util.List;

/** 保存 U9 料号选择；全部已确认时允许空 selections 重试下级 BOM 检查。 */
public record ElectronicDrawingMaterialResolutionRequest(
    Integer expectedTaskVersion,
    Long expectedSourceVersionId,
    List<Selection> selections) {

  public ElectronicDrawingMaterialResolutionRequest {
    selections = selections == null ? List.of() : List.copyOf(selections);
  }

  public record Selection(Long sourceNodeId, String materialCode) {}
}
