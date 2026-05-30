package com.sanhua.marketingcost.dto.quotebom;

import java.util.List;

public record BomSupplementTaskDetailResponse(
    TaskHeader task,
    QuoteProductBomPreparationPreview preparation,
    List<QuoteBomSupplementDetailDto> supplementLines,
    PackageReference packageReference,
    List<QuoteBomPackageReferenceDetailDto> packageLines,
    List<ChangeLogLine> changeLogs,
    List<CompleteBomLine> completeBomPreview) {

  public record TaskHeader(
      Long taskId,
      String taskNo,
      String taskType,
      String productCode,
      String productName,
      String productModel,
      String customerCode,
      String packageMethod,
      String missingBomScope,
      String missingReason,
      String taskStatus,
      String technicianName,
      String remark) {}

  public record PackageReference(
      Long packageReferenceId,
      String bareProductCode,
      String referenceFinishedCode,
      String sourceTopProductCode,
      String periodMonth,
      String referenceStatus,
      Integer selectedLineCount,
      Integer editedFlag) {}

  public record ChangeLogLine(
      Long id,
      Long bizDetailId,
      String fieldName,
      String fieldLabel,
      String beforeValue,
      String afterValue,
      String changeReason,
      Long changedBy,
      String changedByName,
      String changedAt,
      String changeSource) {}

  public record CompleteBomLine(
      String sourceType,
      Integer lineNo,
      Integer level,
      String topProductCode,
      String parentCode,
      String materialCode,
      String materialName,
      String materialSpec,
      String materialModel,
      String drawingNo,
      String shapeAttr,
      String mainCategoryCode,
      String unit,
      String referenceFinishedCode,
      String sourceTopProductCode,
      java.math.BigDecimal qtyPerParent,
      java.math.BigDecimal qtyPerTop,
      java.math.BigDecimal parentBaseQty,
      String remark,
      Boolean edited) {}
}
