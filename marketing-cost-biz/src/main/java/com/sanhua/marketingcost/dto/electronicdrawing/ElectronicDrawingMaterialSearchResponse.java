package com.sanhua.marketingcost.dto.electronicdrawing;

import java.util.List;

/** 财务报价员主动搜索当前任务物料组织中的 U9 料品档案结果。 */
public record ElectronicDrawingMaterialSearchResponse(
    Long productTaskId,
    Long sourceVersionId,
    String searchType,
    String keyword,
    List<Option> options) {

  public ElectronicDrawingMaterialSearchResponse {
    options = options == null ? List.of() : List.copyOf(options);
  }

  public record Option(
      String materialCode,
      String materialName,
      String materialSpec,
      String materialModel,
      String drawingNo,
      String materialNature,
      String unit,
      String mainCategoryCode,
      String mainCategoryName) {}
}
