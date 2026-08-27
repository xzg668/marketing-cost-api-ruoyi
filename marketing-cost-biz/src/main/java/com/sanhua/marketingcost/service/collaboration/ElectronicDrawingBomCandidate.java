package com.sanhua.marketingcost.service.collaboration;

import java.math.BigDecimal;
import java.util.List;

/** Excel 与未来电子图库接口共用的候选 BOM 结构；此时明细料号尚可为空。 */
public record ElectronicDrawingBomCandidate(
    String sourceFileName,
    String sourceSheetName,
    List<Node> nodes) {

  public ElectronicDrawingBomCandidate {
    nodes = nodes == null ? List.of() : List.copyOf(nodes);
  }

  public record RootProduct(
      String productCode,
      String temporaryProductKey,
      String productName,
      String productSpec,
      String productModel,
      String drawingNo,
      String materialNature,
      String unit) {}

  public record Node(
      String nodeKey,
      String parentNodeKey,
      int level,
      String sourceSequence,
      String materialCode,
      String drawingCode,
      String materialName,
      String materialSpec,
      String materialModel,
      String materialNature,
      String unit,
      BigDecimal quantity,
      BigDecimal referenceWeight,
      String sourceMaterial,
      String importanceClass,
      String hsfRiskClass,
      String remark,
      Integer sourceRowNumber) {}
}
