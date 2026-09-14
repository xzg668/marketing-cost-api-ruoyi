package com.sanhua.marketingcost.dto.electronicdrawing;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 电子图库当前源版本的料号解析状态；搜索结果不随页面初始化预加载。 */
public record ElectronicDrawingMaterialResolutionResponse(
    Long productTaskId,
    Integer taskVersion,
    Long sourceVersionId,
    Integer sourceVersionNo,
    String sourceVersionStatus,
    String electronicDrawingNo,
    String materialOrganizationCode,
    int totalCount,
    int autoMatchedCount,
    int manuallySelectedCount,
    int unmatchedCount,
    int ambiguousCount,
    boolean complete,
    List<Item> items) {

  public ElectronicDrawingMaterialResolutionResponse {
    items = items == null ? List.of() : List.copyOf(items);
  }

  public record Item(
      Long sourceNodeId,
      Integer sourceRowNo,
      String sourceSequence,
      String parentSourceSequence,
      String drawingCode,
      String sourceName,
      BigDecimal quantity,
      String sourceMaterial,
      String matchStatus,
      boolean requiresAction,
      String resolvedMaterialCode,
      String resolvedMaterialName,
      String resolvedMaterialSpec,
      String resolvedMaterialModel,
      String resolvedDrawingNo,
      String resolvedMaterialNature,
      String resolvedUnit,
      String resolvedBy,
      LocalDateTime resolvedAt) {}
}
