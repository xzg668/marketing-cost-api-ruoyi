package com.sanhua.marketingcost.service.electronicdrawing;

import com.sanhua.marketingcost.entity.ElectronicDrawingSourceNode;
import java.time.LocalDateTime;
import java.util.List;

/** 电子图库源节点仓储；不暴露可覆盖 Excel 原始字段的更新操作。 */
public interface ElectronicDrawingSourceNodeRepository {

  void insertAll(Long supplementVersionId, List<ElectronicDrawingSourceNode> sourceNodes);

  List<ElectronicDrawingSourceNode> findByVersionId(Long supplementVersionId);

  List<ElectronicDrawingSourceNode> findPendingByVersionId(Long supplementVersionId);

  boolean updateResolution(
      Long sourceNodeId,
      String expectedStatus,
      String targetStatus,
      String resolvedMaterialCode,
      String resolvedBy,
      LocalDateTime resolvedAt);
}
