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
    List<Item> items,
    String accountingMonth, String workflowStage, boolean bomComposed, boolean bomPublished) {

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
      LocalDateTime resolvedAt,
      String processingState,
      String processingMessage,
      boolean canModify,
      String quantityUnit,
      Long correctedFromSourceNodeId,
      List<U9Child> u9Children) {
    public Item {
      u9Children = u9Children == null ? List.of() : List.copyOf(u9Children);
    }
  }

  /** 当前制造节点采用的 U9 下级；节点键只在该图库分支内唯一，不能作为图库修正行提交。 */
  public record U9Child(
      String nodeKey, String parentNodeKey, Long sourceRawHierarchyId, Long sourceU9BomId,
      String materialCode, String materialName, String materialSpec, String drawingNo,
      String shapeAttr, BigDecimal quantity, BigDecimal parentBaseQty, String unit,
      String bomPurpose, String bomVersion, Integer sortSeq) {}
}
