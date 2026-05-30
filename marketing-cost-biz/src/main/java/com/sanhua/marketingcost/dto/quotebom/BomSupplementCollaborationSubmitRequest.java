package com.sanhua.marketingcost.dto.quotebom;

import java.math.BigDecimal;
import java.util.List;

public record BomSupplementCollaborationSubmitRequest(
    Long submittedBy,
    String submittedByName,
    String remark,
    List<SupplementLine> supplementLines,
    PackageReferenceSelection packageReference) {

  public record SupplementLine(
      Integer lineNo,
      Integer level,
      String parentCode,
      String materialCode,
      String materialName,
      String materialSpec,
      String materialModel,
      String drawingNo,
      String shapeAttr,
      String mainCategoryCode,
      String sourceCategory,
      String costElementCode,
      String bomPurpose,
      String bomVersion,
      BigDecimal qtyPerParent,
      BigDecimal qtyPerTop,
      BigDecimal parentBaseQty,
      String unit,
      String path,
      Integer sortSeq,
      String remark) {}

  public record PackageReferenceSelection(
      String referenceFinishedCode,
      String sourceTopProductCode,
      String periodMonth,
      List<PackageLineSelection> selectedLines,
      String remark) {}

  public record PackageLineSelection(
      Long snapshotId,
      Long snapshotDetailId,
      Integer lineNo,
      Boolean selected,
      BigDecimal adjustedPackageQtyPerParent,
      BigDecimal adjustedPackageQtyPerTop,
      BigDecimal adjustedPackageParentBaseQty,
      BigDecimal adjustedChildQtyPerParent,
      BigDecimal adjustedChildQtyPerTop,
      BigDecimal adjustedChildParentBaseQty,
      String remark) {}
}
