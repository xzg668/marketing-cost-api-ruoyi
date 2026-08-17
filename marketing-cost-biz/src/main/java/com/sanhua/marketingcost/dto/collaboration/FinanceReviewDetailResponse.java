package com.sanhua.marketingcost.dto.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record FinanceReviewDetailResponse(
    Long reviewId, String reviewNo, String oaNo, int reviewRound, String status,
    int sourceTaskVersion, int productCount, int priceDraftCount, int passedCount,
    int rejectedCount, boolean canApprove, List<Product> products, List<Item> items) {
  public record Product(Long productTaskId, String productCode, String productName,
      String productSpec, String productModel, String taskStatus) {}
  public record Item(Long reviewItemId, Long productTaskId, String itemType,
      String itemTypeLabel, String summary, String decision, String decisionReason) {}
  public record ItemDetail(Long reviewItemId, Long productTaskId, String productCode,
      String productName, String itemType, String itemTypeLabel, String summary,
      JsonNode technicalInput, JsonNode validation, String decision, String decisionReason) {}
}
