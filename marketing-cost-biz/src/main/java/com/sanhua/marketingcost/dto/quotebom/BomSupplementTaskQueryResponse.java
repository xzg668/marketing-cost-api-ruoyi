package com.sanhua.marketingcost.dto.quotebom;

import java.time.LocalDateTime;
import java.util.List;

public record BomSupplementTaskQueryResponse(
    long total,
    List<Row> rows) {

  public record Row(
      Long taskId,
      String taskNo,
      String taskType,
      String productCode,
      String productName,
      String productModel,
      String customerCode,
      String periodMonth,
      String taskStatus,
      String preparationStatus,
      String reviewStatus,
      String technicianName,
      String referenceFinishedCode,
      String sourceTopProductCode,
      int supplementLineCount,
      int packageLineCount,
      Integer editedFlag,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {}
}
